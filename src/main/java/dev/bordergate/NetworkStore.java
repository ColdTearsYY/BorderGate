package dev.bordergate;

import dev.bordergate.core.QuestCycle;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

final class NetworkStore implements AutoCloseable {
    record TaskSnapshot(String taskId, String serverId, String title, String cadence, long endsAt,
                        int requiredCount, Map<String, BigDecimal> targets,
                        Map<String, BigDecimal> contributed, Map<UUID, BigDecimal> scores, String status,
                        BigDecimal rewardPerPlayer, String rewardMode, int rewardScale,
                        String templateId, boolean automaticBorder, double maximumChangePercent) {}

    record Reservation(long id, String taskId, UUID playerId, String resource, BigDecimal amount, BigDecimal score) {}

    private final BorderGatePlugin plugin;
    private final String serverId;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private volatile boolean available;

    NetworkStore(BorderGatePlugin plugin) {
        this.plugin = plugin;
        ConfigurationSection db = plugin.getConfig().getConfigurationSection("network.mysql");
        this.serverId = plugin.getConfig().getString("network.server-id", "server-1");
        this.jdbcUrl = "jdbc:mysql://" + db.getString("host", "127.0.0.1") + ":" + db.getInt("port", 3306) + "/"
                + db.getString("database", "bordergate") + "?useSSL=" + db.getBoolean("use-ssl", false)
                + "&serverTimezone=UTC&characterEncoding=utf8";
        this.username = db.getString("username", "bordergate");
        this.password = db.getString("password", "");
        initialize();
    }

    String serverId() { return serverId; }
    boolean available() { return available; }

    Connection claimConnection() throws SQLException { return connect(); }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bordergate_task (task_id VARCHAR(190) PRIMARY KEY, server_id VARCHAR(80) NOT NULL, cadence VARCHAR(16) NOT NULL, title VARCHAR(160) NOT NULL, ends_at BIGINT NOT NULL, required_count INT NOT NULL, reward_pool DECIMAL(30,6) NOT NULL DEFAULT 0, reward_per_player DECIMAL(30,6) NOT NULL DEFAULT 0, reward_mode VARCHAR(16) NOT NULL DEFAULT 'fixed', reward_scale INT NOT NULL DEFAULT 2, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', updated_at BIGINT NOT NULL) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bordergate_target (task_id VARCHAR(190) NOT NULL, resource VARCHAR(120) NOT NULL, target_amount DECIMAL(30,6) NOT NULL, PRIMARY KEY(task_id, resource), FOREIGN KEY(task_id) REFERENCES bordergate_task(task_id) ON DELETE CASCADE) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bordergate_contribution (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(190) NOT NULL, player_uuid CHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL, resource VARCHAR(120) NOT NULL, amount DECIMAL(30,6) NOT NULL, score DECIMAL(30,8) NOT NULL, source_server VARCHAR(80) NOT NULL, status VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, INDEX idx_bg_task_resource(task_id, resource, status), INDEX idx_bg_task_player(task_id, player_uuid), FOREIGN KEY(task_id) REFERENCES bordergate_task(task_id) ON DELETE CASCADE) ENGINE=InnoDB");
            addColumnIfMissing(connection, "reward_per_player", "DECIMAL(30,6) NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "reward_mode", "VARCHAR(16) NOT NULL DEFAULT 'fixed'");
            addColumnIfMissing(connection, "reward_scale", "INT NOT NULL DEFAULT 2");
            addColumnIfMissing(connection, "template_id", "VARCHAR(80) NOT NULL DEFAULT 'manual'");
            addColumnIfMissing(connection, "automatic_border", "BOOLEAN NOT NULL DEFAULT FALSE");
            addColumnIfMissing(connection, "maximum_change_percent", "DOUBLE NOT NULL DEFAULT 5");
            available = true;
            plugin.getLogger().info("BorderGate MySQL network store connected.");
        } catch (SQLException | LinkageError ex) {
            available = false;
            plugin.getLogger().severe("MySQL network store unavailable; cross-server tasks are disabled: " + ex.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String column, String definition) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bordergate_task' AND column_name=?")) {
            query.setString(1, column);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    try (Statement alter = connection.createStatement()) {
                        alter.executeUpdate("ALTER TABLE bordergate_task ADD COLUMN " + column + " " + definition);
                    }
                }
            }
        }
    }

    void syncTask(QuestCycle cycle, long endsAt, BigDecimal rewardPerPlayer, String rewardMode, int rewardScale) {
        syncTask(cycle, endsAt, rewardPerPlayer, rewardMode, rewardScale, "manual", false, 5);
    }

    void syncTask(QuestCycle cycle, long endsAt, BigDecimal rewardPerPlayer, String rewardMode, int rewardScale,
                  String templateId, boolean automaticBorder, double maximumChangePercent) {
        if (!available || cycle == null) return;
        String insert = "INSERT INTO bordergate_task(task_id,server_id,cadence,title,ends_at,required_count,reward_pool,reward_per_player,reward_mode,reward_scale,status,updated_at,template_id,automatic_border,maximum_change_percent) VALUES(?,?,?,?,?,?,0,?,?,?,'ACTIVE',?,?,?,?) ON DUPLICATE KEY UPDATE task_id=task_id";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, cycle.id()); statement.setString(2, serverId);
                    statement.setString(3, cycle.cadence()); statement.setString(4, cycle.title());
                    statement.setLong(5, endsAt); statement.setInt(6, cycle.requiredCount());
                    statement.setBigDecimal(7, rewardPerPlayer == null ? BigDecimal.ZERO : rewardPerPlayer);
                    statement.setString(8, rewardMode == null ? "fixed" : rewardMode);
                    statement.setInt(9, Math.max(0, Math.min(rewardScale, 8)));
                    statement.setLong(10, System.currentTimeMillis());
                    statement.setString(11, templateId); statement.setBoolean(12, automaticBorder);
                    statement.setDouble(13, maximumChangePercent); statement.executeUpdate();
                }
                // Definitions are immutable once targets exist. The task-row write locks concurrent creators.
                boolean initialized;
                try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM bordergate_target WHERE task_id=?")) {
                    query.setString(1, cycle.id());
                    try (ResultSet rows = query.executeQuery()) { rows.next(); initialized = rows.getInt(1) > 0; }
                }
                if (!initialized) try (PreparedStatement target = connection.prepareStatement(
                        "INSERT INTO bordergate_target(task_id,resource,target_amount) VALUES(?,?,?)")) {
                    for (Map.Entry<String, BigDecimal> entry : cycle.targets().entrySet()) {
                        target.setString(1, cycle.id()); target.setString(2, entry.getKey()); target.setBigDecimal(3, entry.getValue()); target.addBatch();
                    }
                    target.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) { connection.rollback(); throw ex; }
            publish("task:" + cycle.id());
        } catch (SQLException ex) { plugin.getLogger().warning("Could not sync task " + cycle.id() + ": " + ex.getMessage()); }
    }

    Reservation reserve(String taskId, UUID uuid, String playerName, String resource, BigDecimal amount, BigDecimal score) {
        if (!available) return null;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT status FROM bordergate_task WHERE task_id=? FOR UPDATE")) {
                    lock.setString(1, taskId);
                    try (ResultSet row = lock.executeQuery()) {
                        if (!row.next() || !row.getString(1).equals("ACTIVE")) {
                            connection.rollback();
                            return null;
                        }
                    }
                }
                BigDecimal target;
                try (PreparedStatement query = connection.prepareStatement("SELECT target_amount FROM bordergate_target WHERE task_id=? AND resource=?")) {
                    query.setString(1, taskId);
                    query.setString(2, resource);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return null;
                        }
                        target = row.getBigDecimal(1);
                    }
                }
                try (PreparedStatement expire = connection.prepareStatement(
                        "UPDATE bordergate_contribution SET status='CANCELLED' WHERE task_id=? AND status='PENDING' AND created_at<?")) {
                    expire.setString(1, taskId);
                    expire.setLong(2, System.currentTimeMillis() - 300_000L);
                    expire.executeUpdate();
                }
                BigDecimal current;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT COALESCE(SUM(amount),0) FROM bordergate_contribution WHERE task_id=? AND resource=? AND status IN ('PENDING','COMMITTED')")) {
                    query.setString(1, taskId);
                    query.setString(2, resource);
                    try (ResultSet row = query.executeQuery()) {
                        row.next();
                        current = row.getBigDecimal(1);
                    }
                }
                if (amount == null || amount.signum() <= 0 || amount.compareTo(target.subtract(current)) > 0) {
                    connection.rollback();
                    return null;
                }
                long id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO bordergate_contribution(task_id,player_uuid,player_name,resource,amount,score,source_server,status,created_at) VALUES(?,?,?,?,?,?,?,'PENDING',?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, taskId);
                    insert.setString(2, uuid.toString());
                    insert.setString(3, playerName);
                    insert.setString(4, resource);
                    insert.setBigDecimal(5, amount);
                    insert.setBigDecimal(6, score);
                    insert.setString(7, serverId);
                    insert.setLong(8, System.currentTimeMillis());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) {
                            connection.rollback();
                            return null;
                        }
                        id = keys.getLong(1);
                    }
                }
                connection.commit();
                return new Reservation(id, taskId, uuid, resource, amount, score);
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not reserve cross-server contribution: " + ex.getMessage());
            return null;
        }
    }

    boolean complete(Reservation reservation, boolean paid) {
        if (!available || reservation == null) return false;
        String status = paid ? "COMMITTED" : "CANCELLED";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                boolean updated;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE bordergate_contribution SET status=? WHERE id=? AND status='PENDING'")) {
                    update.setString(1, status);
                    update.setLong(2, reservation.id());
                    updated = update.executeUpdate() == 1;
                }
                if (paid && updated) {
                    try (PreparedStatement updateTask = connection.prepareStatement(
                            "UPDATE bordergate_task SET status='COMPLETE',updated_at=? WHERE task_id=? AND status='ACTIVE' AND (SELECT COUNT(*) FROM (SELECT t.resource FROM bordergate_target t LEFT JOIN bordergate_contribution c ON c.task_id=t.task_id AND c.resource=t.resource AND c.status='COMMITTED' WHERE t.task_id=? GROUP BY t.resource,t.target_amount HAVING COALESCE(SUM(c.amount),0)>=t.target_amount) completed_targets)>=required_count")) {
                        updateTask.setLong(1, System.currentTimeMillis());
                        updateTask.setString(2, reservation.taskId());
                        updateTask.setString(3, reservation.taskId());
                        updateTask.executeUpdate();
                    }
                }
                connection.commit();
                if (paid && updated) publish("progress:" + reservation.taskId());
                return updated;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not finalize cross-server contribution: " + ex.getMessage());
            return false;
        }
    }

    List<TaskSnapshot> activeTasks() { return taskSnapshots(List.of()); }

    List<TaskSnapshot> taskSnapshots(List<String> currentIds) {
        if (!available) return List.of();
        List<TaskSnapshot> tasks = new ArrayList<>();
        String extra = currentIds.isEmpty() ? "" : " OR task_id IN (" + String.join(",", java.util.Collections.nCopies(currentIds.size(), "?")) + ")";
        String sql = "SELECT task_id,server_id,cadence,title,ends_at,required_count,status,reward_per_player,reward_mode,reward_scale,template_id,automatic_border,maximum_change_percent FROM bordergate_task WHERE status IN ('ACTIVE','COMPLETE')" + extra + " ORDER BY server_id,cadence";
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(sql)) {
            for (int i = 0; i < currentIds.size(); i++) query.setString(i + 1, currentIds.get(i));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    Map<String, BigDecimal> targets = new LinkedHashMap<>();
                    Map<String, BigDecimal> progress = new LinkedHashMap<>();
                    try (PreparedStatement targetQuery = connection.prepareStatement(
                            "SELECT t.resource,t.target_amount,COALESCE(SUM(CASE WHEN c.status='COMMITTED' THEN c.amount ELSE 0 END),0) FROM bordergate_target t LEFT JOIN bordergate_contribution c ON c.task_id=t.task_id AND c.resource=t.resource WHERE t.task_id=? GROUP BY t.resource,t.target_amount ORDER BY t.resource")) {
                        targetQuery.setString(1, id);
                        try (ResultSet targetRows = targetQuery.executeQuery()) {
                            while (targetRows.next()) {
                                targets.put(targetRows.getString(1), targetRows.getBigDecimal(2));
                                progress.put(targetRows.getString(1), targetRows.getBigDecimal(3));
                            }
                        }
                    }
                    Map<UUID, BigDecimal> scores = new LinkedHashMap<>();
                    try (PreparedStatement scoreQuery = connection.prepareStatement(
                            "SELECT player_uuid,SUM(score) FROM bordergate_contribution WHERE task_id=? AND status='COMMITTED' GROUP BY player_uuid ORDER BY SUM(score) DESC")) {
                        scoreQuery.setString(1, id);
                        try (ResultSet scoreRows = scoreQuery.executeQuery()) {
                            while (scoreRows.next()) scores.put(UUID.fromString(scoreRows.getString(1)), scoreRows.getBigDecimal(2));
                        }
                    }
                    tasks.add(new TaskSnapshot(id, rows.getString(2), rows.getString(4), rows.getString(3), rows.getLong(5),
                            rows.getInt(6), targets, progress, scores, rows.getString(7), rows.getBigDecimal(8),
                            rows.getString(9), rows.getInt(10), rows.getString(11), rows.getBoolean(12), rows.getDouble(13)));
                }
            }
            return tasks;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not load cross-server tasks: " + ex.getMessage());
            return List.of();
        }
    }

    boolean markSettled(String taskId, boolean success) {
        if (!available) return false;
        String status = success ? "COMPLETE" : "FAILED";
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE bordergate_task SET status=?,updated_at=? WHERE task_id=? AND server_id=? AND status IN ('ACTIVE','COMPLETE')")) {
            update.setString(1, status);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, taskId);
            update.setString(4, serverId);
            boolean updated = update.executeUpdate() == 1;
            if (updated) publish("settled:" + taskId);
            return updated;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not settle network task: " + ex.getMessage());
            return false;
        }
    }

    boolean claimRewards(String taskId) {
        if (!available) return true;
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE bordergate_task SET status='PAID',updated_at=? WHERE task_id=? AND server_id=? AND status='COMPLETE'")) {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, taskId);
            update.setString(3, serverId);
            return update.executeUpdate() == 1;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not claim network rewards: " + ex.getMessage());
            return false;
        }
    }

    private void publish(String message) {
        ConfigurationSection redis = plugin.getConfig().getConfigurationSection("network.redis");
        if (redis == null || !redis.getBoolean("enabled", false)) return;
        String host = redis.getString("host", "127.0.0.1");
        int port = redis.getInt("port", 6379);
        String channel = redis.getString("channel", "bordergate:updates");
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(1500);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String secret = redis.getString("password", "");
            if (!secret.isBlank()) {
                writeCommand(out, "AUTH", secret);
                readReply(in);
            }
            writeCommand(out, "PUBLISH", channel, serverId + "|" + message);
            readReply(in);
        } catch (IOException ex) {
            plugin.getLogger().fine("Redis update publish skipped: " + ex.getMessage());
        }
    }

    private void writeCommand(BufferedOutputStream out, String... values) throws IOException {
        out.write(("*" + values.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    private void readReply(BufferedInputStream in) throws IOException {
        while (in.read() != '\n') {
            if (in.available() == 0) break;
        }
    }

    @Override
    public void close() {
        available = false;
    }
}
