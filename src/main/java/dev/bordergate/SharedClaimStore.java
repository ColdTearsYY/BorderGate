package dev.bordergate;

import dev.bordergate.core.CooperationLedger.Claim;
import dev.bordergate.core.LandAffiliation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Shared advisory plans, deliberately separate from paid contribution reservations. */
final class SharedClaimStore {
    private final BorderGatePlugin plugin;
    private final NetworkStore network;
    private volatile boolean healthy;
    SharedClaimStore(BorderGatePlugin plugin, NetworkStore network) {
        this.plugin = plugin; this.network = network;
        if (!network.available()) return;
        try (Connection connection = network.claimConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bordergate_claim (claim_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(190) NOT NULL, resource VARCHAR(120) NOT NULL, player_uuid CHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL, team_id VARCHAR(120) NOT NULL DEFAULT '', team_name VARCHAR(64) NOT NULL DEFAULT '', amount DECIMAL(30,6) NOT NULL, expires_at BIGINT NOT NULL, source_server VARCHAR(80) NOT NULL, UNIQUE KEY uq_bg_claim(task_id,resource,player_uuid), INDEX idx_bg_claim_task(task_id,expires_at), FOREIGN KEY(task_id) REFERENCES bordergate_task(task_id) ON DELETE CASCADE) ENGINE=InnoDB");
            try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "bordergate_claim", "land_identity")) {
                if (!columns.next()) try { statement.executeUpdate("ALTER TABLE bordergate_claim ADD COLUMN land_identity BOOLEAN NOT NULL DEFAULT FALSE"); }
                catch (SQLException ex) { if (ex.getErrorCode() != 1060) throw ex; }
            }
            // Legacy column names are retained; new nonempty identities are Lands only.
            try (PreparedStatement retire = connection.prepareStatement("UPDATE bordergate_claim SET amount=0 WHERE source_server=? AND amount>0 AND team_id<>'' AND land_identity=FALSE")) {
                retire.setString(1, network.serverId()); retire.executeUpdate();
            }
            healthy = true;
        } catch (SQLException ex) { failed(ex); }
    }
    boolean available() { return healthy && network.available(); }
    private void failed(SQLException ex) { healthy = false; plugin.getLogger().warning("Shared cooperation claims paused: " + ex.getMessage()); }
    List<Claim> claims(String taskId, long now) {
        if (!available()) return null;
        try (Connection connection = network.claimConnection()) {
            int required;
            try (PreparedStatement task = connection.prepareStatement("SELECT status,required_count,ends_at FROM bordergate_task WHERE task_id=?")) {
                task.setString(1, taskId);
                try (ResultSet result = task.executeQuery()) {
                    if (!result.next() || !"ACTIVE".equals(result.getString(1)) || result.getLong(3) <= now) return List.of();
                    required = result.getInt(2);
                }
            }
            java.util.Set<String> finished = new java.util.HashSet<>();
            try (PreparedStatement targets = connection.prepareStatement("SELECT t.resource,t.target_amount,COALESCE(SUM(CASE WHEN c.status='COMMITTED' THEN c.amount ELSE 0 END),0) FROM bordergate_target t LEFT JOIN bordergate_contribution c ON c.task_id=t.task_id AND c.resource=t.resource WHERE t.task_id=? GROUP BY t.resource,t.target_amount")) {
                targets.setString(1, taskId);
                try (ResultSet result = targets.executeQuery()) {
                    while (result.next()) if (result.getBigDecimal(3).compareTo(result.getBigDecimal(2)) >= 0) finished.add(result.getString(1));
                }
            }
            if (finished.size() >= required) return List.of();
            return rows(connection, taskId, now, false).stream().filter(c -> !finished.contains(c.resource())).toList();
        }
        catch (SQLException ex) { failed(ex); return null; }
    }
    private List<Claim> rows(Connection connection, String taskId, long now, boolean lock) throws SQLException {
        List<Claim> claims = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT claim_id,resource,player_uuid,team_id,team_name,amount,expires_at FROM bordergate_claim WHERE task_id=? AND amount>0 AND expires_at>? AND (team_id='' OR (land_identity=TRUE AND team_id LIKE 'lands:%')) ORDER BY claim_id" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, taskId); query.setLong(2, now);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    try {
                        String landId = result.getString(4), landName = result.getString(5);
                        if (landId.isEmpty() != landName.isEmpty() || !landId.isEmpty() && !LandAffiliation.validClaimLand(landId, landName)) continue;
                        claims.add(new Claim(result.getString(1), taskId, result.getString(2), UUID.fromString(result.getString(3)),
                            landId, landName, result.getBigDecimal(6), result.getLong(7))); }
                    catch (IllegalArgumentException ex) { plugin.getLogger().warning("Ignoring malformed shared claim identity"); }
                }
            }
        }
        return List.copyOf(claims);
    }
    Claim claim(String id, String taskId, String resource, UUID player, String playerName, String landId, String landName,
                BigDecimal amount, long now, long expiresAt) {
        if (!available() || player == null || amount == null || amount.signum() <= 0 || expiresAt <= now || landId == null || landName == null) return null;
        try { if (!landId.isEmpty()) new LandAffiliation(landId, landName, 0); else if (!landName.isEmpty()) return null; }
        catch (IllegalArgumentException ex) { return null; }
        try (Connection connection = network.claimConnection()) {
            connection.setAutoCommit(false);
            try {
                int required; long deadline;
                try (PreparedStatement query = connection.prepareStatement("SELECT status,required_count,ends_at FROM bordergate_task WHERE task_id=? FOR UPDATE")) {
                    query.setString(1, taskId);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next() || !result.getString(1).equals("ACTIVE") || result.getLong(3) <= now) { connection.rollback(); return null; }
                        required = result.getInt(2); deadline = result.getLong(3);
                    }
                }
                int done = 0; BigDecimal remaining = BigDecimal.ZERO;
                try (PreparedStatement query = connection.prepareStatement("SELECT t.resource,t.target_amount,COALESCE(SUM(CASE WHEN c.status='COMMITTED' OR (c.status='PENDING' AND c.created_at>=?) THEN c.amount ELSE 0 END),0) FROM bordergate_target t LEFT JOIN bordergate_contribution c ON c.task_id=t.task_id AND c.resource=t.resource WHERE t.task_id=? GROUP BY t.resource,t.target_amount")) {
                    query.setLong(1, now - 300000L); query.setString(2, taskId);
                    try (ResultSet result = query.executeQuery()) {
                        while (result.next()) {
                            BigDecimal gap = result.getBigDecimal(2).subtract(result.getBigDecimal(3));
                            if (gap.signum() <= 0) done++;
                            if (result.getString(1).equals(resource)) remaining = gap.max(BigDecimal.ZERO);
                        }
                    }
                }
                if (done >= required || remaining.signum() <= 0) { connection.rollback(); return null; }
                BigDecimal other = BigDecimal.ZERO; String actualId = id;
                for (Claim claim : rows(connection, taskId, now, true)) if (claim.resource().equals(resource)) {
                    if (claim.playerId().equals(player)) actualId = claim.id(); else other = other.add(claim.amount());
                }
                if (amount.compareTo(remaining.subtract(other).max(BigDecimal.ZERO)) > 0) { connection.rollback(); return null; }
                // Expired rows retained for audit; unique key permits renewal without deleting anything.
                try (PreparedStatement query = connection.prepareStatement("SELECT claim_id FROM bordergate_claim WHERE task_id=? AND resource=? AND player_uuid=?")) {
                    query.setString(1, taskId); query.setString(2, resource); query.setString(3, player.toString());
                    try (ResultSet result = query.executeQuery()) { if (result.next()) actualId = result.getString(1); }
                }
                long end = Math.min(expiresAt, deadline);
                try (PreparedStatement write = connection.prepareStatement("INSERT INTO bordergate_claim(claim_id,task_id,resource,player_uuid,player_name,team_id,team_name,amount,expires_at,source_server,land_identity) VALUES(?,?,?,?,?,?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),team_id=VALUES(team_id),team_name=VALUES(team_name),amount=VALUES(amount),expires_at=VALUES(expires_at),source_server=VALUES(source_server),land_identity=TRUE")) {
                    write.setString(1, actualId); write.setString(2, taskId); write.setString(3, resource); write.setString(4, player.toString());
                    write.setString(5, playerName); write.setString(6, landId); write.setString(7, landName); write.setBigDecimal(8, amount);
                    write.setLong(9, end); write.setString(10, network.serverId()); write.executeUpdate();
                }
                connection.commit(); return new Claim(actualId, taskId, resource, player, landId, landName, amount, end);
            } catch (SQLException ex) { connection.rollback(); throw ex; }
        } catch (SQLException ex) { failed(ex); return null; }
    }
    boolean cancel(String id, UUID player) {
        if (!available() || player == null) return false;
        try (Connection connection = network.claimConnection(); PreparedStatement query = connection.prepareStatement(
                "UPDATE bordergate_claim SET amount=0 WHERE claim_id=? AND player_uuid=? AND amount>0")) {
            query.setString(1, id); query.setString(2, player.toString()); return query.executeUpdate() == 1;
        } catch (SQLException ex) { failed(ex); return false; }
    }
    void fulfil(String taskId, String resource, UUID player, String landId, BigDecimal amount, long now) {
        if (!available() || amount == null || amount.signum() <= 0) return;
        try (Connection connection = network.claimConnection()) {
            connection.setAutoCommit(false);
            try {
                // Same task-row -> claim-row lock order as claim(), never reversed.
                try (PreparedStatement lock = connection.prepareStatement("SELECT task_id FROM bordergate_task WHERE task_id=? FOR UPDATE")) {
                    lock.setString(1, taskId); try (ResultSet result = lock.executeQuery()) { if (!result.next()) { connection.rollback(); return; } }
                }
                List<Claim> selected = new ArrayList<>(rows(connection, taskId, now, true).stream().filter(c -> c.resource().equals(resource)
                        && (c.playerId().equals(player) || !landId.isEmpty() && c.landId().equals(landId))).toList());
                selected.sort(Comparator.comparing((Claim c) -> !c.playerId().equals(player)).thenComparing(Claim::id));
                BigDecimal left = amount;
                try (PreparedStatement write = connection.prepareStatement("UPDATE bordergate_claim SET amount=? WHERE claim_id=?")) {
                    for (Claim claim : selected) {
                        if (left.signum() <= 0) break;
                        BigDecimal used = left.min(claim.amount()); left = left.subtract(used);
                        write.setBigDecimal(1, claim.amount().subtract(used)); write.setString(2, claim.id()); write.addBatch();
                    }
                    write.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) { connection.rollback(); throw ex; }
        } catch (SQLException ex) { failed(ex); }
    }
    void close(String taskId, String resource) {
        if (!available()) return;
        try (Connection connection = network.claimConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT task_id FROM bordergate_task WHERE task_id=? FOR UPDATE")) {
                    lock.setString(1, taskId); try (ResultSet result = lock.executeQuery()) { if (!result.next()) { connection.rollback(); return; } }
                }
                try (PreparedStatement write = connection.prepareStatement("UPDATE bordergate_claim SET amount=0 WHERE task_id=? AND amount>0" + (resource == null ? "" : " AND resource=?"))) {
                    write.setString(1, taskId); if (resource != null) write.setString(2, resource); write.executeUpdate();
                }
                connection.commit();
            } catch (SQLException ex) { connection.rollback(); throw ex; }
        } catch (SQLException ex) { failed(ex); }
    }
}
