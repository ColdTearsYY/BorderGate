package dev.bordergate.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java cooperation ledger.
 *
 * <p>Identity is external: a player may belong to any number of Lands territories, and the ledger
 * only records what an already-trusted caller reports at the moment of an event. There is no local
 * team registry, no join/leave operation, no member cap and no roster to consult. Everything that
 * used to be squad data is kept as an opaque, read-only legacy archive that is never credited,
 * never restored as active state and never converted into territory scores.
 */
public final class CooperationLedger {
    public record Claim(String id, String taskId, String resource, UUID playerId, String landId,
                        String landName, BigDecimal amount, long expiresAt) {}
    public record Profile(boolean reminders, BigDecimal totalScore, int resourceFinishes, int streak, LocalDate lastDay) {}
    public record Feedback(boolean firstParticipation, boolean finishedResource, int streak,
                           BigDecimal totalScore, String landName) {}

    private static final long REMINDER_COOLDOWN = 600_000L;
    private static final String LEGACY_SQUADS = "legacy-squads";
    private static final String LEGACY_SQUAD_CLAIMS = "legacy-squad-claims";
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Map<UUID, Long> activities = new HashMap<>();
    private final Set<String> eventIds = new HashSet<>();
    private final Map<String, BigDecimal> landScores = new LinkedHashMap<>();
    private final Set<String> reminderKeys = new HashSet<>();
    private final Map<UUID, Long> reminderCooldowns = new HashMap<>();
    private Map<String, Object> legacySquads = Map.of();
    private List<Map<String, Object>> legacySquadClaims = List.of();

    public synchronized Claim claim(String id, String taskId, String resource, UUID player, String landId,
                                    String landName, BigDecimal amount, BigDecimal remaining, long now, long expiresAt) {
        if (!validId(id) || !validId(taskId) || !validText(resource, 128) || player == null || amount == null
                || amount.signum() <= 0 || remaining == null || remaining.signum() < 0 || expiresAt <= now) return null;
        String lid = landId == null ? "" : landId;
        String lname = landName == null ? "" : landName;
        if (lid.isEmpty() != lname.isEmpty()) return null;
        if (!lid.isEmpty() && !LandAffiliation.validClaimLand(lid, lname)) return null;
        Claim byId = claims.get(id);
        if (byId != null && (!byId.playerId().equals(player) || !byId.taskId().equals(taskId) || !byId.resource().equals(resource))) return null;
        Claim previousSelf = claims.values().stream().filter(c -> c.taskId().equals(taskId) && c.resource().equals(resource)
                && c.playerId().equals(player) && c.expiresAt() > now).findFirst().orElse(null);
        BigDecimal reserved = BigDecimal.ZERO;
        for (Claim c : claims.values()) if (c.expiresAt() > now && !c.playerId().equals(player)
                && c.taskId().equals(taskId) && c.resource().equals(resource)) reserved = reserved.add(c.amount());
        if (reserved.add(amount).compareTo(remaining) > 0) return null;
        if (previousSelf != null) claims.remove(previousSelf.id());
        Claim result = new Claim(id, taskId, resource, player, lid, lname, amount, expiresAt);
        claims.put(id, result);
        return result;
    }
    public synchronized List<Claim> claims(String taskId, long now) {
        claims.values().removeIf(c -> c.expiresAt() <= now);
        return claims.values().stream().filter(c -> taskId == null || c.taskId().equals(taskId)).toList();
    }
    public synchronized boolean cancelClaim(String id, UUID player) {
        Claim c = claims.get(id);
        return c != null && c.playerId().equals(player) && claims.remove(id) != null;
    }
    public synchronized void fulfil(String taskId, String resource, UUID player, String landId, BigDecimal amount, long now) {
        if (!validText(taskId, 128) || !validText(resource, 128) || player == null || amount == null || amount.signum() <= 0) return;
        String lid = landId == null ? "" : landId;
        claims.values().removeIf(c -> c.expiresAt() <= now);
        BigDecimal left = amount;
        List<Claim> eligible = claims.values().stream().filter(c -> c.taskId().equals(taskId) && c.resource().equals(resource)
                && ((c.playerId().equals(player)) || (!lid.isEmpty() && lid.equals(c.landId()))))
                .sorted(Comparator.comparing((Claim c) -> !c.playerId().equals(player)).thenComparing(Claim::id)).toList();
        for (Claim c : eligible) {
            if (left.signum() <= 0) break;
            BigDecimal take = left.min(c.amount());
            BigDecimal remain = c.amount().subtract(take);
            if (remain.signum() == 0) claims.remove(c.id());
            else claims.put(c.id(), new Claim(c.id(), c.taskId(), c.resource(), c.playerId(), c.landId(), c.landName(), remain, c.expiresAt()));
            left = left.subtract(take);
        }
    }
    public synchronized void closeClaims(String taskId, String resource) {
        claims.values().removeIf(c -> c.taskId().equals(taskId) && (resource == null || c.resource().equals(resource)));
    }

    /** Historical score credited to one external territory handle; renaming keeps the same key. */
    public synchronized BigDecimal landScore(String id) {
        return id == null ? BigDecimal.ZERO : landScores.getOrDefault(id, BigDecimal.ZERO);
    }
    /** Immutable snapshot of every territory score; a fresh copy, safe to iterate after mutation. */
    public synchronized Map<String, BigDecimal> landScores() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(landScores));
    }

    public synchronized Profile profile(UUID player) {
        return profiles.getOrDefault(player, new Profile(true, BigDecimal.ZERO, 0, 0, null));
    }
    public synchronized void setReminders(UUID player, boolean enabled) {
        if (player == null) return;
        Profile p = profile(player);
        profiles.put(player, new Profile(enabled, p.totalScore(), p.resourceFinishes(), p.streak(), p.lastDay()));
    }
    public synchronized boolean remindersEnabled(UUID player) { return profile(player).reminders(); }

    /** Personal contribution: no territory is credited and the feedback carries no territory name. */
    public synchronized Feedback recordContribution(String eventId, UUID player, LocalDate day, BigDecimal score, boolean finishedResource) {
        return recordContribution(eventId, player, day, score, finishedResource, null);
    }

    /**
     * Contribution with an explicit external territory snapshot. The land is credited at event time
     * and the name is only a display copy: a later rename keeps the score on the same id and no
     * history is ever transferred between territories. Replaying an event id is a no-op.
     */
    public synchronized Feedback recordContribution(String eventId, UUID player, LocalDate day, BigDecimal score,
                                                    boolean finishedResource, LandAffiliation land) {
        if (!validId(eventId) || player == null || day == null || score == null || score.signum() <= 0 || eventIds.contains(eventId)) return null;
        eventIds.add(eventId);
        Profile old = profile(player);
        int streak = old.streak();
        LocalDate last = old.lastDay();
        if (last == null) streak = 1;
        else if (day.isAfter(last)) streak = day.equals(last.plusDays(1)) ? streak + 1 : 1;
        if (last == null || day.isAfter(last)) last = day;
        Profile updated = new Profile(old.reminders(), old.totalScore().add(score), old.resourceFinishes() + (finishedResource ? 1 : 0), streak, last);
        profiles.put(player, updated);
        String landName = "";
        if (land != null) { landName = land.name(); landScores.merge(land.id(), score, BigDecimal::add); }
        return new Feedback(old.totalScore().signum() == 0, finishedResource, streak, updated.totalScore(), landName);
    }

    public synchronized void markActivity(UUID player, long time) { if (player != null) activities.merge(player, time, Math::max); }
    public synchronized int activePlayers(long since) { return (int) activities.values().stream().filter(t -> t >= since).count(); }

    public synchronized boolean allowReminder(UUID player, String taskId, String type, long now) {
        if (player == null || !validId(taskId) || type == null || !Set.of("new", "near", "complete").contains(type) || !remindersEnabled(player)) return false;
        String key = player + "|" + taskId + "|" + type;
        if (reminderKeys.contains(key) || reminderKeys.stream().filter(k -> k.startsWith(player + "|" + taskId + "|")).count() >= 3) return false;
        if (!type.equals("complete")) {
            Long previous = reminderCooldowns.get(player);
            if (previous != null && now >= previous && now - previous < REMINDER_COOLDOWN) return false;
        }
        reminderKeys.add(key);
        if (!type.equals("complete")) reminderCooldowns.put(player, now);
        return true;
    }

    public synchronized Map<String, Object> exportState() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> claimRows = new ArrayList<>();
        for (Claim c : claims.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id()); row.put("taskId", c.taskId()); row.put("resource", c.resource());
            row.put("playerId", c.playerId().toString()); row.put("landId", c.landId()); row.put("landName", c.landName());
            row.put("amount", c.amount().toPlainString()); row.put("expiresAt", c.expiresAt());
            claimRows.add(row);
        }
        out.put("claims", claimRows);
        Map<String, Object> profileRows = new LinkedHashMap<>();
        profiles.forEach((id, p) -> { Map<String, Object> row = new LinkedHashMap<>(); row.put("reminders", p.reminders()); row.put("totalScore", p.totalScore().toPlainString()); row.put("resourceFinishes", p.resourceFinishes()); row.put("streak", p.streak()); row.put("lastDay", p.lastDay() == null ? "" : p.lastDay().toString()); profileRows.put(id.toString(), row); });
        out.put("profiles", profileRows);
        Map<String, Object> activityRows = new LinkedHashMap<>(); activities.forEach((id, t) -> activityRows.put(id.toString(), t)); out.put("activities", activityRows);
        out.put("eventIds", List.copyOf(eventIds));
        Map<String, Object> scores = new LinkedHashMap<>(); landScores.forEach((id, v) -> scores.put(id, v.toPlainString())); out.put("landScores", scores);
        out.put("reminderKeys", List.copyOf(reminderKeys));
        Map<String, Object> cooldowns = new LinkedHashMap<>(); reminderCooldowns.forEach((id, t) -> cooldowns.put(id.toString(), t)); out.put("reminderCooldowns", cooldowns);
        if (!legacySquads.isEmpty()) out.put(LEGACY_SQUADS, legacySquads);
        if (!legacySquadClaims.isEmpty()) out.put(LEGACY_SQUAD_CLAIMS, legacySquadClaims);
        return freezeMap(out);
    }

    public synchronized void restoreState(Map<?, ?> state) {
        claims.clear(); profiles.clear(); activities.clear(); eventIds.clear(); landScores.clear();
        reminderKeys.clear(); reminderCooldowns.clear(); legacySquads = Map.of(); legacySquadClaims = List.of();
        if (state == null) return;
        readLegacyArchive(state);
        Object rawClaims = state.get("claims");
        if (rawClaims instanceof List<?> rows) for (Object row : rows) try {
            Map<?, ?> m = map(row);
            if (text(m.get("landId")).isEmpty() && !text(m.get("teamId")).isEmpty()) { archiveClaim(row); continue; }
            String landId = text(m.get("landId"));
            String landName = text(m.get("landName"));
            if (landId.isEmpty() != landName.isEmpty() || !landId.isEmpty() && !LandAffiliation.validClaimLand(landId, landName)) { archiveClaim(row); continue; }
            Claim c = new Claim(text(m.get("id")), text(m.get("taskId")), text(m.get("resource")),
                    UUID.fromString(text(m.get("playerId"))), landId, landName, decimal(m.get("amount")), number(m.get("expiresAt")));
            if (validId(c.id()) && validId(c.taskId()) && validText(c.resource(), 128) && c.amount().signum() > 0 && c.expiresAt() > 0
                    && !claims.containsKey(c.id())
                    && claims.values().stream().noneMatch(a -> a.taskId().equals(c.taskId()) && a.resource().equals(c.resource()) && a.playerId().equals(c.playerId()))) claims.put(c.id(), c);
        } catch (RuntimeException ignored) {}
        Object rawProfiles = state.get("profiles"); if (rawProfiles instanceof Map<?, ?> rows) for (var e : rows.entrySet()) try { UUID id = UUID.fromString(text(e.getKey())); Map<?, ?> m = map(e.getValue()); BigDecimal score = decimal(m.get("totalScore")); int finishes = (int) number(m.get("resourceFinishes")), streak = (int) number(m.get("streak")); String d = text(m.get("lastDay")); if (score.signum() < 0 || finishes < 0 || streak < 0) continue; profiles.put(id, new Profile(Boolean.TRUE.equals(m.get("reminders")), score, finishes, streak, d.isEmpty() ? null : LocalDate.parse(d))); } catch (RuntimeException ignored) {}
        readLongMap(state.get("activities"), activities);
        Object ids = state.get("eventIds"); if (ids instanceof List<?> list) for (Object x : list) if (x instanceof String s && validId(s)) eventIds.add(s);
        Object rawScores = state.get("landScores"); if (rawScores instanceof Map<?, ?> scores) for (var e : scores.entrySet()) try { String id = text(e.getKey()); BigDecimal v = decimal(e.getValue()); if (LandAffiliation.validId(id) && v.signum() >= 0) landScores.put(id, v); } catch (RuntimeException ignored) {}
        Object keys = state.get("reminderKeys"); if (keys instanceof List<?> list) for (Object x : list) if (x instanceof String s && validReminderKey(s)) reminderKeys.add(s);
        readLongMap(state.get("reminderCooldowns"), reminderCooldowns);
    }

    /**
     * Old squad data is frozen, not migrated: the rows are kept verbatim so nothing is lost, but they
     * never become active claims, never become territory scores and never re-create membership.
     */
    private void readLegacyArchive(Map<?, ?> state) {
        Object archived = state.get(LEGACY_SQUADS);
        Map<String, Object> frozen = new LinkedHashMap<>();
        if (archived instanceof Map<?, ?> rows) {
            rows.forEach((k, v) -> { if (k instanceof String s) frozen.put(s, copy(v)); });
        } else {
            Object teams = state.get("teams");
            Object teamScores = state.get("teamScores");
            if (teams != null) frozen.put("teams", copy(teams));
            if (teamScores != null) frozen.put("teamScores", copy(teamScores));
        }
        legacySquads = freezeRow(frozen);
        Object archivedClaims = state.get(LEGACY_SQUAD_CLAIMS);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (archivedClaims instanceof List<?> list) for (Object row : list) { Map<String, Object> frozenRow = freezeRow(row); if (frozenRow != null) rows.add(frozenRow); }
        legacySquadClaims = List.copyOf(rows);
    }
    private void archiveClaim(Object row) {
        Map<String, Object> frozenRow = freezeRow(row);
        if (frozenRow == null) return;
        List<Map<String, Object>> rows = new ArrayList<>(legacySquadClaims);
        rows.add(frozenRow);
        legacySquadClaims = List.copyOf(rows);
    }

    private static void readLongMap(Object raw, Map<UUID, Long> target) { if (raw instanceof Map<?, ?> m) for (var e : m.entrySet()) try { target.put(UUID.fromString(text(e.getKey())), number(e.getValue())); } catch (RuntimeException ignored) {} }
    private static boolean validReminderKey(String key) { String[] p = key.split("\\|", -1); return p.length == 3 && validId(p[1]) && Set.of("new", "near", "complete").contains(p[2]) && parseUuid(p[0]); }
    private static boolean parseUuid(String s) { try { UUID.fromString(s); return true; } catch (RuntimeException e) { return false; } }
    private static Map<?, ?> map(Object o) { if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException(); return m; }
    private static String text(Object o) { if (o == null) return ""; if (!(o instanceof String s)) throw new IllegalArgumentException(); return s; }
    private static BigDecimal decimal(Object o) { return new BigDecimal(o.toString()); }
    private static long number(Object o) { if (!(o instanceof Number n)) throw new IllegalArgumentException(); return new BigDecimal(n.toString()).longValueExact(); }
    private static boolean validId(String s) { return s != null && s.matches("[A-Za-z0-9_.:-]{1,190}"); }
    private static boolean validText(String s, int max) { return s != null && !s.isBlank() && s.length() <= max; }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> { if (k instanceof String s) out.put(s, copy(v)); });
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(CooperationLedger::copy).toList();
        return value;
    }
    private static Map<String, Object> freezeRow(Object row) {
        if (!(row instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> { if (k instanceof String s) out.put(s, copy(v)); });
        return Collections.unmodifiableMap(out);
    }
    private static Map<String, Object> freezeMap(Map<String, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, freeze(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> { if (key instanceof String s) copy.put(s, freeze(item)); });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(CooperationLedger::freeze).toList();
        return value;
    }
}
