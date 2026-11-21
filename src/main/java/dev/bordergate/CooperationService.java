package dev.bordergate;

import dev.bordergate.core.AdaptiveTaskPolicy;
import dev.bordergate.core.CooperationLedger;
import dev.bordergate.core.LandAffiliation;
import dev.bordergate.core.TownLeaderboard;
import dev.bordergate.core.QuestCycle;
import dev.bordergate.core.RecommendationPlanner;
import dev.bordergate.core.TaskSchedule;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Cooperation is advisory: it never replaces payment/reservation or owns a border. */
final class CooperationService {
    private final BorderGatePlugin plugin;
    private final CooperationLedger ledger = new CooperationLedger();
    private final File file;
    private final Map<String,Long> started = new LinkedHashMap<>();
    private final Map<String,Long> completed = new LinkedHashMap<>();
    private final Map<String,Double> scales = new LinkedHashMap<>();
    private final List<AdaptiveTaskPolicy.Observation> observations = new ArrayList<>();
    private final Map<String,Long> invitations = new LinkedHashMap<>();
    private final Map<String,String> landSelections = new LinkedHashMap<>();
    private LandsHook lands;
    private SharedClaimStore shared;
    private boolean migrated;

    CooperationService(BorderGatePlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "cooperation.yml");
        lands = new LandsHook(plugin, localServer());
        load();
        if (plugin.networkStore() != null) shared = new SharedClaimStore(plugin, plugin.networkStore());
    }
    boolean enabled() { return plugin.getConfig().getBoolean("cooperation.enabled", true); }
    CooperationLedger ledger() { return ledger; }
    boolean sharedClaims() { return plugin.getConfig().getBoolean("network.enabled", false); }
    boolean claimsAvailable() { return !sharedClaims() || shared != null && shared.available(); }
    void refreshClaims() { lands.refresh(); if (plugin.networkStore() != null) shared = new SharedClaimStore(plugin, plugin.networkStore()); }


    synchronized void migrateHistory() {
        if (migrated) return;
        int index = 0;
        for (QuestService.HistoryEntry entry : plugin.questService().history()) {
            ledger.recordContribution("legacy:" + index++, entry.playerId(), day(entry.timestamp()), entry.score(), false);
            ledger.markActivity(entry.playerId(), entry.timestamp());
        }
        migrated = true;
        save();
    }
    void onJoin(Player player) { if (enabled()) { ledger.markActivity(player.getUniqueId(), System.currentTimeMillis()); save(); } }
    private LocalDate day(long now) {
        return new TaskSchedule(plugin.getConfig().getString("schedule.timezone", "UTC"),
                plugin.getConfig().getInt("schedule.rollover-hour", 0)).periodDate("daily", now);
    }
    boolean actionable(String taskId) {
        QuestCycle cycle = plugin.questService().cycle(taskId);
        return enabled() && cycle != null && !cycle.isComplete() && !plugin.questService().isSettled(taskId)
                && plugin.questService().endsAt(taskId) > System.currentTimeMillis();
    }
    Map<String,BigDecimal> inventory(Player player) {
        Map<String,BigDecimal> result = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getAmount() > 0 && !stack.getType().isAir())
                result.merge("item:" + stack.getType().name(), BigDecimal.valueOf(stack.getAmount()), BigDecimal::add);
        }
        return result;
    }
    static double cost(String resource) {
        return switch (resource) {
            case "item:ENCHANTING_TABLE", "item:BEEHIVE", "item:CAKE", "item:NAUTILUS_SHELL", "item:IRON_BLOCK", "item:GOLD_BLOCK" -> 8;
            case "item:COMPARATOR", "item:BOOKSHELF", "item:HONEYCOMB", "item:PRISMARINE_SHARD", "item:REPEATER", "item:LEAD" -> 4;
            case "item:IRON_INGOT", "item:GOLD_INGOT", "item:LEATHER", "item:BOOK", "item:RAW_IRON", "item:RAW_GOLD" -> 2;
            default -> 1;
        };
    }
    RecommendationPlanner.Plan plan(Player player, String taskId) {
        if (!actionable(taskId)) return new RecommendationPlanner.Plan(List.of(), List.of());
        Map<String,BigDecimal> promised = new LinkedHashMap<>();
        for (var claim : claims(taskId)) promised.merge(claim.resource(), claim.amount(), BigDecimal::add);
        return RecommendationPlanner.plan(plugin.questService().cycle(taskId), inventory(player), promised, CooperationService::cost);
    }
    String recommendedTask(Player player) {
        String fallback = null;
        for (String cadence : TaskSchedule.CADENCES) {
            String id = plugin.questService().currentTaskId(cadence);
            if (!actionable(id)) continue;
            if (fallback == null) fallback = id;
            if (plan(player, id).recommendations().stream().anyMatch(r -> r.available().signum() > 0)) return id;
        }
        return fallback;
    }
    List<CooperationLedger.Claim> claims(String taskId) {
        long now = System.currentTimeMillis();
        if (!actionable(taskId)) return List.of();
        List<CooperationLedger.Claim> rows;
        if (!sharedClaims()) rows = ledger.claims(taskId, now).stream()
                .filter(c -> plugin.questService().cycle(taskId).remaining(c.resource()).signum() > 0).toList();
        else {
            if (shared == null || !shared.available()) return List.of();
            rows = shared.claims(taskId, now);
            if (rows == null) return List.of();
        }
        List<CooperationLedger.Claim> valid = new ArrayList<>();
        boolean canCheck = landsAvailable();
        for (var claim : rows) {
            if (!claim.landId().isEmpty() && !claim.landId().startsWith("lands:")) continue;
            if (canCheck && lands.local(claim.landId()) && !lands.member(claim.playerId(), claim.landId()) && lands.healthy()) {
                if (cancelClaim(claim.playerId(), claim.id())) continue;
                // Persistence failed: do not advertise capacity that storage still reserves.
            }
            valid.add(claim);
        }
        return List.copyOf(valid);
    }
    String claim(Player player, String taskId, String resource, boolean landClaim) {
        if (!actionable(taskId)) return "&c任务已完成、到期或不可认领。";
        if (!claimsAvailable()) return "&c共享认领服务不可用，暂不创建本地替代认领。";
        LandAffiliation land = landClaim ? selectedLand(player.getUniqueId()) : null;
        if (landClaim && land == null) return "&e请在 Lands 领地菜单选择你有权限的领地；领地 API 不可用时只能个人认领。";
        QuestCycle cycle = plugin.questService().cycle(taskId);
        BigDecimal remaining = cycle.remaining(resource);
        BigDecimal promisedByOthers = claims(taskId).stream()
                .filter(c -> c.resource().equals(resource) && !c.playerId().equals(player.getUniqueId()))
                .map(CooperationLedger.Claim::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal free = remaining.subtract(promisedByOthers).max(BigDecimal.ZERO);
        // Default partial commitment: a quarter of the gap, never all merely by opening a menu.
        BigDecimal amount = remaining.divide(BigDecimal.valueOf(4), 0, java.math.RoundingMode.CEILING)
                .max(BigDecimal.ONE).min(free);
        if (amount.signum() <= 0) return "&e该缺口已有认领计划；你仍可正常投入资源。";
        long now = System.currentTimeMillis();
        long expiry = Math.min(plugin.questService().endsAt(taskId), now + 3600000L);
        String landId = landClaim ? land.id() : "", landName = landClaim ? land.name() : "";
        var before = ledger.exportState();
        var result = sharedClaims() ? shared.claim(UUID.randomUUID().toString(), taskId, resource,
                player.getUniqueId(), player.getName(), landId, landName, amount, now, expiry)
                : ledger.claim(UUID.randomUUID().toString(), taskId, resource, player.getUniqueId(), landId, landName, amount, remaining, now, expiry);
        if (result == null) return "&c认领未保存，可能缺口已变化或服务不可用；没有扣除资源。";
        if (!sharedClaims() && !save()) { ledger.restoreState(before); return "&c认领保存失败，已撤回本次计划。"; }
        return "&a已认领 " + result.resource() + " × " + number(result.amount()) + "，最多1小时；只是计划，不会扣资源或限制他人投入。";
    }
    boolean cancelClaim(UUID player, String id) {
        var before = ledger.exportState();
        if (sharedClaims()) return shared != null && shared.available() && shared.cancel(id, player);
        if (!ledger.cancelClaim(id, player)) return false;
        if (save()) return true;
        ledger.restoreState(before); return false;
    }
    boolean landsAvailable() { return enabled() && lands.available(); }
    List<LandAffiliation> playerLands(UUID player) { return enabled() ? lands.memberships(player) : List.of(); }
    List<TownLeaderboard.Entry> townLeaderboard() {
        if (!enabled()) return List.of();
        List<LandAffiliation> current = lands.allLands();
        return current == null ? null : TownLeaderboard.rank(current, ledger.landScores());
    }
    LandAffiliation selectedLand(UUID player) {
        List<LandAffiliation> choices = playerLands(player);
        String selected;
        synchronized (this) { selected = landSelections.get(player.toString()); }
        for (var land : choices) if (land.id().equals(selected)) return land;
        LandAffiliation editing = lands.editing(player);
        if (editing != null) for (var land : choices) if (land.id().equals(editing.id())) return land;
        return choices.size() == 1 ? choices.getFirst() : null;
    }
    synchronized boolean selectLand(UUID player, String id) {
        if (playerLands(player).stream().noneMatch(land -> land.id().equals(id))) return false;
        String key = player.toString(), previous = landSelections.put(key, id);
        if (save()) return true;
        if (previous == null) landSelections.remove(key); else landSelections.put(key, previous);
        return false;
    }
    String landStatus(UUID player) {
        if (!landsAvailable()) return lands.problem();
        LandAffiliation selected = selectedLand(player);
        return selected == null ? "未选择领地（请在菜单选择，或在 Lands 中设定编辑领地）" : selected.name();
    }
    boolean toggleReminders(UUID player) {
        var before = ledger.exportState();
        ledger.setReminders(player, !ledger.remindersEnabled(player));
        if (!save()) ledger.restoreState(before);
        return ledger.remindersEnabled(player);
    }
    synchronized void invite(Player player, String taskId) {
        if (!actionable(taskId)) { player.sendMessage(plugin.colorMessage("&e本任务已不需要协作投入。")); return; }
        long now = System.currentTimeMillis();
        String prefix = player.getUniqueId() + "|", key = prefix + taskId;
        if (invitations.containsKey(key) || invitations.entrySet().stream().anyMatch(e -> e.getKey().startsWith(prefix) && now - e.getValue() < 600000L)) {
            player.sendMessage(plugin.colorMessage("&e协作邀请刚发送过，请稍后再试。")); return;
        }
        invitations.put(key, now);
        if (!save()) { invitations.remove(key); player.sendMessage(plugin.colorMessage("&c邀请未保存，请稍后再试。")); return; }
        QuestCycle cycle = plugin.questService().cycle(taskId);
        notifyPlayers(taskId, "near", "&b“" + player.getName() + "”邀请伙伴协作：" + cycle.title() + "。可用 /bg view 查询日、周、月任务；提交请前往并点击边界维护员 NPC。", player.getUniqueId());
        player.sendMessage(plugin.colorMessage("&a已请求低频邀请；仅开启提醒、通过冷却的在线伙伴会收到。"));
    }
    CooperationLedger.Feedback contributed(Player player, String taskId, String resource, BigDecimal amount, BigDecimal score,
                     BigDecimal previousRemaining, String eventId) {
        if (!enabled()) return null;
        long now = System.currentTimeMillis();
        LandAffiliation land = selectedLand(player.getUniqueId());
        var feedback = ledger.recordContribution(eventId, player.getUniqueId(), day(now), score,
                previousRemaining.signum() > 0 && amount.compareTo(previousRemaining) >= 0, land);
        if (feedback == null) return null;
        claims(taskId); // Revoke only positively confirmed invalid local Lands plans before fulfilment.
        String landId = land == null ? "" : land.id();
        if (sharedClaims()) { if (shared != null && shared.available()) shared.fulfil(taskId, resource, player.getUniqueId(), landId, amount, now); }
        else ledger.fulfil(taskId, resource, player.getUniqueId(), landId, amount, now);
        ledger.markActivity(player.getUniqueId(), now);
        QuestCycle cycle = plugin.questService().cycle(taskId);
        if (cycle != null && (cycle.isComplete() || cycle.remaining(resource).signum() == 0)) closeClaims(taskId, cycle.isComplete() ? null : resource);
        synchronized (this) { if (cycle != null && cycle.isComplete()) completed.putIfAbsent(taskId, now); }
        save();
        return feedback;
    }
    private void closeClaims(String taskId, String resource) {
        if (sharedClaims()) { if (shared != null && shared.available()) shared.close(taskId, resource); }
        else ledger.closeClaims(taskId, resource);
    }
    synchronized void taskStarted(String id, long now) { started.putIfAbsent(id, now); }
    synchronized double scale(String cadence) {
        return enabled() && plugin.getConfig().getBoolean("cooperation.adaptive.enabled", true) ? scales.getOrDefault(cadence, 1d) : 1d;
    }
    synchronized Set<String> stalled(String cadence) {
        return enabled() && plugin.getConfig().getBoolean("cooperation.adaptive.enabled", true)
                ? AdaptiveTaskPolicy.stalledResources(observations, cadence) : Set.of();
    }
    synchronized void settled(String id, boolean success) {
        if (!enabled() || !localTask(id) || observations.stream().anyMatch(o -> o.taskId().equals(id))) return;
        QuestCycle cycle = plugin.questService().cycle(id);
        if (cycle == null) return;
        closeClaims(id, null);
        if (success) notifyPlayers(id, "complete", "&a共同建设完成：" + cycle.title() + "！感谢所有参与者。 &7" + plugin.borderStatus() + " | 本服总上限 " + plugin.effectiveMaximum(), null);
        if (!plugin.questService().automaticBorder(id)) { save(); return; }
        long now = System.currentTimeMillis(), start = started.getOrDefault(id, now);
        long finish = completed.getOrDefault(id, now);
        double elapsed = Math.max(0, Math.min(1, (finish - start) / (double) Math.max(1, plugin.questService().endsAt(id) - start)));
        Map<String,Double> fractions = new LinkedHashMap<>();
        List<String> necessary = success ? List.copyOf(cycle.targets().keySet())
                : RecommendationPlanner.plan(cycle, Map.of(), Map.of(), CooperationService::cost).path();
        for (String resource : necessary) {
            BigDecimal target = cycle.targets().get(resource);
            if (target.signum() > 0) fractions.put(resource, cycle.contributed().getOrDefault(resource, BigDecimal.ZERO)
                    .divide(target, java.math.MathContext.DECIMAL64).doubleValue());
        }
        int active = ledger.activePlayers(now - 7L * 86400000);
        observations.add(new AdaptiveTaskPolicy.Observation(id, cycle.cadence(), success,
                cycle.completionPercent().doubleValue() / 100, elapsed, cycle.participantCount(), active, fractions));
        scales.put(cycle.cadence(), AdaptiveTaskPolicy.nextScale(scales.getOrDefault(cycle.cadence(), 1d), observations, cycle.cadence(), active));
        // Keep bounded per-cadence evidence, without allowing old task IDs to re-enter the active window.
        for (String cadence : TaskSchedule.CADENCES) {
            List<AdaptiveTaskPolicy.Observation> own = observations.stream().filter(o -> o.cadence().equals(cadence)).toList();
            if (own.size() > 24) observations.removeAll(own.subList(0, own.size() - 24));
        }
        save();
    }
    void tick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (String cadence : TaskSchedule.CADENCES) {
            String id = plugin.questService().currentTaskId(cadence);
            QuestCycle cycle = plugin.questService().cycle(id);
            if (cycle == null || !localTask(id)) continue;
            synchronized (this) { started.putIfAbsent(id, now); if (cycle.isComplete()) completed.putIfAbsent(id, now); }
            if (!actionable(id)) continue;
            var path = RecommendationPlanner.plan(cycle, Map.of(), Map.of(), CooperationService::cost);
            boolean near = cycle.requiredCount() - cycle.completedResourceCount() == 1
                    && path.recommendations().stream().anyMatch(r -> r.remaining().compareTo(cycle.targets().get(r.resource()).multiply(new BigDecimal("0.25"))) <= 0);
            if (near) {
                var item = path.recommendations().getFirst();
                notifyPlayers(id, "near", "&b建设接近完成：还差 " + item.resource() + " × " + number(item.remaining()) + "。可用 /bg view 查询进度；提交请前往并点击边界维护员 NPC。", null);
            } else notifyPlayers(id, "new", "&a协作任务：" + cycle.title() + "，完成 " + cycle.requiredCount() + " 种即可。可用 /bg view 查询日、周、月任务；提交请前往并点击边界维护员 NPC。", null);
        }
        save();
    }
    private void notifyPlayers(String id, String type, String message, UUID exclude) {
        if (!plugin.getConfig().getBoolean("cooperation.reminders.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(exclude) || !player.hasPermission("bordergate.use")) continue;
            player.getScheduler().run(plugin, task -> {
                synchronized (CooperationService.this) {
                    var before = ledger.exportState();
                    if (!enabled() || !ledger.allowReminder(player.getUniqueId(), id, type, System.currentTimeMillis())) return;
                    if (!save()) { ledger.restoreState(before); return; }
                    player.sendMessage(plugin.colorMessage(message));
                }
            }, null);
        }
    }
    List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("合作引导: " + (enabled() ? "开启" : "关闭") + " | 认领: " + (sharedClaims() ? claimsAvailable() ? "MySQL共享" : "共享服务暂停" : "本地"));
        lines.add("协作单位: Lands 领地 | API: " + (landsAvailable() ? "可用" : lands.problem()) + " | 近7天活动玩家: " + ledger.activePlayers(System.currentTimeMillis() - 7L * 86400000));
        for (String cadence : TaskSchedule.CADENCES) lines.add(cadence + " 新任务数量倍率: " + scale(cadence) + " | 暂避资源: " + stalled(cadence));
        return lines;
    }
    private String localServer() { return plugin.networkStore() == null ? plugin.getConfig().getString("network.server-id", "server-1") : plugin.networkStore().serverId(); }
    private boolean localTask(String id) { return !sharedClaims() || id.startsWith(localServer() + ":"); }
    static String number(BigDecimal amount) { return amount.stripTrailingZeros().toPlainString(); }

    synchronized boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 3); yaml.set("ledger", ledger.exportState()); yaml.set("migrated-history", migrated);
        yaml.set("started", started); yaml.set("completed", completed); yaml.set("scales", scales); yaml.set("invitations", invitations);
        yaml.set("land-selections", landSelections);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var o : observations) {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("task", o.taskId()); row.put("cadence", o.cadence());
            row.put("success", o.success()); row.put("completion", o.completionFraction()); row.put("elapsed", o.elapsedFraction());
            row.put("participants", o.participants()); row.put("active", o.activePlayers()); row.put("resources", o.resourceFractions()); rows.add(row);
        }
        yaml.set("observations", rows);
        try {
            Files.createDirectories(file.toPath().getParent()); File tmp = new File(file.getParentFile(), "cooperation.yml.tmp"); yaml.save(tmp);
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ex) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException ex) { plugin.getLogger().severe("Cannot save cooperation.yml: " + ex.getMessage()); return false; }
    }
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("ledger");
        if (section != null) ledger.restoreState(toMap(section));
        migrated = yaml.getInt("version", 1) >= 2 && yaml.getBoolean("migrated-history", false);
        ConfigurationSection invites = yaml.getConfigurationSection("invitations");
        if (invites != null) for (String key : invites.getKeys(false)) invitations.put(key, invites.getLong(key));
        ConfigurationSection selections = yaml.getConfigurationSection("land-selections");
        if (selections != null) for (String key : selections.getKeys(false)) try {
            UUID.fromString(key); String id = selections.getString(key);
            if (id != null && id.startsWith("lands:")) landSelections.put(key, id);
        } catch (IllegalArgumentException ignored) { }
        ConfigurationSection starts = yaml.getConfigurationSection("started"), finishes = yaml.getConfigurationSection("completed");
        if (starts != null) for (String id : starts.getKeys(false)) started.put(id, starts.getLong(id));
        if (finishes != null) for (String id : finishes.getKeys(false)) completed.put(id, finishes.getLong(id));
        for (String cadence : TaskSchedule.CADENCES) {
            double scale = yaml.getDouble("scales." + cadence, 1); scales.put(cadence, Double.isFinite(scale) ? Math.max(.5, Math.min(2, scale)) : 1);
        }
        for (Map<?,?> row : yaml.getMapList("observations")) try {
            Map<String,Double> fractions = new LinkedHashMap<>(); Object resources = row.get("resources");
            if (resources instanceof Map<?,?> values) values.forEach((k,v) -> { if (v instanceof Number n && Double.isFinite(n.doubleValue())) fractions.put(k.toString(), n.doubleValue()); });
            String cadence = row.get("cadence").toString(); if (!TaskSchedule.CADENCES.contains(cadence)) continue;
            observations.add(new AdaptiveTaskPolicy.Observation(row.get("task").toString(), cadence, Boolean.parseBoolean(row.get("success").toString()),
                    Double.parseDouble(row.get("completion").toString()), Double.parseDouble(row.get("elapsed").toString()),
                    Integer.parseInt(row.get("participants").toString()), Integer.parseInt(row.get("active").toString()), fractions));
        } catch (RuntimeException ex) { plugin.getLogger().warning("Ignoring malformed cooperation observation"); }
    }
    private Map<String,Object> toMap(ConfigurationSection section) {
        Map<String,Object> values = new LinkedHashMap<>();
        section.getValues(false).forEach((k,v) -> values.put(k, v instanceof ConfigurationSection child ? toMap(child) : v));
        return values;
    }
}
