package dev.bordergate;

import dev.bordergate.core.QuestCycle;
import dev.bordergate.core.QuestSettlementPolicy;
import dev.bordergate.core.TaskSchedule;
import dev.bordergate.core.TaskAvailability;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

final class QuestService {
    record HistoryEntry(String taskId, String cadence, UUID playerId, String playerName,
                        String resource, BigDecimal amount, BigDecimal score, long timestamp) {}

    private final BorderGatePlugin plugin;
    private final File file;
    private TaskSchedule schedule;
    private final Map<String, QuestCycle> cycles = new LinkedHashMap<>();
    private final Map<String, Long> endsAt = new LinkedHashMap<>();
    private final Map<String, Boolean> settled = new LinkedHashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Map<String, String> templateIds = new LinkedHashMap<>();
    private final Map<String, Boolean> automaticBorder = new LinkedHashMap<>();
    private final Map<String, Double> maximumChanges = new LinkedHashMap<>();
    private final Map<String, List<String>> recentTemplates = new LinkedHashMap<>();
    private final Map<String, TaskAvailability> creationFailures = new LinkedHashMap<>();

    QuestService(BorderGatePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "quests.yml");
        load();
    }

    synchronized String formatDeadline(long epochMillis) { return schedule.formatDeadline(epochMillis); }
    synchronized String currentTaskId(String cadence) { return currentTaskId(cadence, System.currentTimeMillis()); }

    synchronized String currentTaskId(String cadence, long now) {
        String id = periodId(cadence, now);
        // The calendar changes immediately, while the settlement tick runs once per minute.
        // Fill only unchecked missing periods; never redraw an existing published task.
        if (TaskSchedule.CADENCES.stream().map(c -> periodId(c, now))
                .anyMatch(current -> !cycles.containsKey(current) && !creationFailures.containsKey(current))) {
            refreshNetworkTasks();
            ensureCurrentPeriods(now);
        }
        return id;
    }

    synchronized TaskAvailability missingTaskAvailability(String cadence) {
        return creationFailures.getOrDefault(periodId(cadence, System.currentTimeMillis()), TaskAvailability.PENDING);
    }
    synchronized List<String> taskIds() { return List.copyOf(cycles.keySet()); }
    synchronized QuestCycle cycle(String id) { return cycles.get(id); }
    synchronized List<HistoryEntry> history() { return List.copyOf(history); }
    synchronized boolean isSettled(String id) { return settled.getOrDefault(id, false); }
    synchronized long endsAt(String id) { return endsAt.getOrDefault(id, 0L); }
    synchronized boolean automaticBorder(String id) { return automaticBorder.getOrDefault(id, false); }
    synchronized double maximumChangePercent(String id) { return maximumChanges.getOrDefault(id, 5.0); }
    synchronized String templateId(String id) { return templateIds.getOrDefault(id, "manual"); }

    /** Reads published periods only; looking at the overview never generates or settles a task. */
    synchronized List<dev.bordergate.core.TaskOverview> overview(long now) {
        refreshNetworkTasks();
        return TaskSchedule.CADENCES.stream().map(cadence -> {
            String id = periodId(cadence, now);
            return dev.bordergate.core.TaskOverview.snapshot(cadence, cycles.get(id), endsAt.getOrDefault(id, 0L),
                    settled.getOrDefault(id, false), now);
        }).toList();
    }

    synchronized List<String> statusLines(long now) {
        refreshNetworkTasks();
        List<String> lines = new ArrayList<>();
        for (String cadence : TaskSchedule.CADENCES) {
            String id = periodId(cadence, now);
            lines.addAll(dev.bordergate.core.TaskStatusFormatter.lines(cadence, cycles.get(id),
                    endsAt.getOrDefault(id, 0L), settled.getOrDefault(id, false), schedule, now));
            if (cycles.containsKey(id)) lines.add(plugin.taskEffect(id));
        }
        return List.copyOf(lines);
    }

    synchronized List<NetworkStore.TaskSnapshot> refreshNetworkTasks() {
        NetworkStore store = plugin.networkStore();
        if (store == null || !store.available()) return List.of();
        long now = System.currentTimeMillis();
        List<NetworkStore.TaskSnapshot> snapshots = store.taskSnapshots(TaskSchedule.CADENCES.stream().map(cadence -> periodId(cadence, now)).toList());
        for (NetworkStore.TaskSnapshot snapshot : snapshots) {
            QuestCycle cycle = cycles.get(snapshot.taskId());
            if (cycle == null || !cycle.targets().equals(snapshot.targets()) || cycle.requiredCount() != snapshot.requiredCount()
                    || !cycle.title().equals(snapshot.title())
                    || snapshot.scoreMultiplier() != null && cycle.scoreMultiplier().compareTo(snapshot.scoreMultiplier()) != 0) {
                cycle = new QuestCycle(snapshot.taskId(), snapshot.title(), snapshot.cadence(), snapshot.targets(), snapshot.requiredCount(),
                        decimal(snapshot.scoreMultiplier(), decimal(plugin.getConfig().get("tasks." + snapshot.cadence() + ".score-multiplier"), BigDecimal.ONE)));
                cycles.put(snapshot.taskId(), cycle);
            }
            cycle.restoreAggregates(snapshot.contributed(), snapshot.scores());
            endsAt.put(snapshot.taskId(), snapshot.endsAt());
            settled.put(snapshot.taskId(), snapshot.status().equals("FAILED") || snapshot.status().equals("PAID"));
            templateIds.put(snapshot.taskId(), snapshot.templateId());
            automaticBorder.put(snapshot.taskId(), snapshot.automaticBorder());
            maximumChanges.put(snapshot.taskId(), snapshot.maximumChangePercent());
        }
        return snapshots;
    }

    synchronized List<Map.Entry<UUID, BigDecimal>> leaderboard(String taskId) {
        refreshNetworkTasks();
        QuestCycle cycle = cycles.get(taskId);
        if (cycle == null) return List.of();
        return cycle.scores().entrySet().stream().sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed()).toList();
    }

    synchronized EconomyBridge.Result contribute(Player player, String taskId, String resource, BigDecimal amount) {
        refreshNetworkTasks();
        QuestCycle cycle = cycles.get(taskId);
        if (cycle == null || settled.getOrDefault(taskId, false) || cycle.isComplete() || endsAt(taskId) <= System.currentTimeMillis()
                || amount == null || amount.signum() <= 0 || amount.compareTo(cycle.remaining(resource)) > 0) {
            return EconomyBridge.Result.INVALID;
        }
        BigDecimal previousRemaining = cycle.remaining(resource);
        NetworkStore store = plugin.networkStore();
        if (store != null && store.available()) {
            BigDecimal score = amount.multiply(BigDecimal.valueOf(100)).divide(cycle.targets().get(resource), java.math.MathContext.DECIMAL64)
                    .multiply(plugin.resourceWeight(resource)).multiply(cycle.scoreMultiplier());
            NetworkStore.Reservation reservation = store.reserve(taskId, player.getUniqueId(), player.getName(), resource, amount, score,
                    cycle.scoreMultiplier(), cycle.targets().get(resource));
            if (reservation == null) return EconomyBridge.Result.INVALID;
            EconomyBridge.Result payment = plugin.economy().charge(player, resource, amount);
            if (payment != EconomyBridge.Result.SUCCESS) {
                store.complete(reservation, false);
                return payment;
            }
            if (!store.complete(reservation, true)) {
                plugin.getLogger().severe("Contribution was charged but network commit failed; reservation " + reservation.id() + " remains for reconciliation.");
                return EconomyBridge.Result.UNAVAILABLE;
            }
            refreshNetworkTasks();
            history.add(new HistoryEntry(taskId, cycle.cadence(), player.getUniqueId(), player.getName(), resource, amount, score, System.currentTimeMillis()));
            save();
            contributionFeedback(player, taskId, resource, amount, score, previousRemaining, "network:" + reservation.id());
            if (settleOnCompletion() && cycle.isComplete() && taskId.startsWith(store.serverId() + ":")) plugin.settleQuest(taskId, true);
            return EconomyBridge.Result.SUCCESS;
        }
        EconomyBridge.Result paid = plugin.economy().charge(player, resource, amount);
        if (paid != EconomyBridge.Result.SUCCESS) return paid;
        BigDecimal before = cycle.score(player.getUniqueId());
        if (!cycle.contribute(player.getUniqueId(), resource, amount, plugin.resourceWeight(resource))) return EconomyBridge.Result.INVALID;
        BigDecimal earned = cycle.score(player.getUniqueId()).subtract(before);
        history.add(new HistoryEntry(taskId, cycle.cadence(), player.getUniqueId(), player.getName(), resource, amount, earned, System.currentTimeMillis()));
        save();
        contributionFeedback(player, taskId, resource, amount, earned, previousRemaining, "local:" + UUID.randomUUID());
        plugin.onQuestProgress(taskId);
        if (settleOnCompletion() && cycle.isComplete()) plugin.settleQuest(taskId, true);
        return EconomyBridge.Result.SUCCESS;
    }

    private void contributionFeedback(Player player, String taskId, String resource, BigDecimal amount,
                                      BigDecimal score, BigDecimal previousRemaining, String eventId) {
        var feedback = plugin.cooperation() == null ? null : plugin.cooperation().contributed(
                player, taskId, resource, amount, score, previousRemaining, eventId);
        QuestCycle cycle = cycles.get(taskId);
        BigDecimal target = cycle.targets().get(resource);
        // Include this committed payment even if a network snapshot refresh temporarily failed.
        BigDecimal done = cycle.contributed().getOrDefault(resource, BigDecimal.ZERO)
                .max(target.subtract(previousRemaining).add(amount)).min(target);
        player.sendMessage(ContributionMessage.create(ResourceDisplay.name(resource, plugin.getConfig()),
                amount, score, cycle.cadence(), cycle.title(), done, target,
                amount.compareTo(previousRemaining) >= 0, feedback));
    }

    private boolean settleOnCompletion() {
        return plugin.getConfig().getBoolean("schedule.settle-on-completion", true);
    }

    synchronized void settleExpired() {
        refreshNetworkTasks();
        long now = System.currentTimeMillis();
        List<String> due = cycles.keySet().stream()
                .filter(id -> !settled.getOrDefault(id, false) && QuestSettlementPolicy.evaluate(now, endsAt(id),
                        cycles.get(id).isComplete(), settleOnCompletion()) != QuestSettlementPolicy.Outcome.PENDING).toList();
        for (String id : due) plugin.settleQuest(id, cycles.get(id).isComplete());
        ensureCurrentPeriods(now);
    }

    synchronized void settle(String id) {
        if (!cycles.containsKey(id) || settled.getOrDefault(id, false)) return;
        settled.put(id, true);
        save();
    }

    synchronized void applyRewards(String taskId) {
        QuestCycle cycle = cycles.get(taskId);
        if (cycle == null) return;
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("tasks." + cycle.cadence() + ".rewards");
        if (rewards == null) return;
        BigDecimal perPlayer = decimal(rewards.get("per-player"), decimal(rewards.get("pool"), BigDecimal.ZERO));
        String mode = rewards.getString("mode", "fixed");
        int scale = Math.max(0, Math.min(8, rewards.getInt("amount-scale", 2)));
        Map<UUID, BigDecimal> shares = cycle.rewardShares(perPlayer, mode, scale);
        BigDecimal totalReward = cycle.totalReward(perPlayer, mode, scale);
        List<String> commands = rewards.getStringList("commands");
        for (Map.Entry<UUID, BigDecimal> entry : shares.entrySet()) {
            String name = playerName(entry.getKey());
            String percent = cycle.playerPercent(entry.getKey()).setScale(2, java.math.RoundingMode.DOWN).toPlainString();
            for (String command : commands) {
                String rendered = command.replace("{player}", name).replace("{uuid}", entry.getKey().toString())
                        .replace("{amount}", entry.getValue().stripTrailingZeros().toPlainString())
                        .replace("{percent}", percent).replace("{task}", taskId)
                        .replace("{participants}", Integer.toString(cycle.participantCount()))
                        .replace("{pool}", totalReward.stripTrailingZeros().toPlainString());
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered));
            }
        }
    }

    synchronized String reload() {
        schedule = new TaskSchedule(plugin.getConfig().getString("schedule.timezone", "UTC"),
                plugin.getConfig().getInt("schedule.rollover-hour", 0));
        long now = System.currentTimeMillis();
        // Published task definitions and history are snapshots; only missing periods use the new config.
        refreshNetworkTasks();
        ensureCurrentPeriods(now);
        save();
        return "配置已热重载。当前进行中的任务、目标、进度和截止时间保持不变；新生成周期采用新任务数量与倍率；历史任务不重开。";
    }

    private String playerName(UUID uuid) {
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).playerId().equals(uuid)) return history.get(i).playerName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    private void ensureCurrentPeriods(long now) {
        for (String cadence : TaskSchedule.CADENCES) {
            String id = periodId(cadence, now);
            if (!cycles.containsKey(id)) {
                creationFailures.remove(id);
                QuestCycle cycle = createCycle(cadence, id);
                if (cycle != null) {
                    cycles.put(id, cycle);
                    endsAt.put(id, nextBoundary(cadence, now));
                    settled.put(id, false);
                }
            }
            QuestCycle current = cycles.get(id);
            NetworkStore store = plugin.networkStore();
            if (current != null && store != null && store.available()) {
                store.syncTask(current, endsAt.get(id), rewardPerPlayer(cadence), rewardMode(cadence), rewardScale(cadence),
                        templateId(id), automaticBorder(id), maximumChangePercent(id));
            }
        }
        cycles.entrySet().removeIf(entry -> !entry.getKey().equals(periodId(entry.getValue().cadence(), now))
                && settled.getOrDefault(entry.getKey(), false));
        templateIds.keySet().retainAll(cycles.keySet());
        automaticBorder.keySet().retainAll(cycles.keySet());
        maximumChanges.keySet().retainAll(cycles.keySet());
        creationFailures.keySet().removeIf(id -> TaskSchedule.CADENCES.stream().noneMatch(c -> id.equals(periodId(c, now))));
        save();
    }

    private void load() {
        schedule = new TaskSchedule(plugin.getConfig().getString("schedule.timezone", "UTC"),
                plugin.getConfig().getInt("schedule.rollover-hour", 0));
        long now = System.currentTimeMillis();
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String cadence : TaskSchedule.CADENCES) recentTemplates.put(cadence, new ArrayList<>(yaml.getStringList("rotation." + cadence)));
            ConfigurationSection states = yaml.getConfigurationSection("cycles");
            if (states != null) for (String id : states.getKeys(false)) {
                ConfigurationSection state = states.getConfigurationSection(id);
                if (state == null) continue;
                String cadence = state.getString("cadence", id.contains("monthly-") ? "monthly" : id.contains("weekly-") ? "weekly" : "daily");
                if (!TaskSchedule.CADENCES.contains(cadence)) { plugin.getLogger().warning("Ignoring unknown task cadence " + cadence); continue; }
                QuestCycle cycle = readCycle(id, cadence, state);
                if (cycle != null) {
                    cycles.put(id, cycle);
                    endsAt.put(id, state.getLong("ends-at", nextBoundary(cadence, now)));
                    settled.put(id, state.getBoolean("settled", false));
                    automaticBorder.put(id, state.getBoolean("automatic-border", false));
                    maximumChanges.put(id, state.getDouble("maximum-change-percent", 5));
                    if (state.contains("template-id")) templateIds.put(id, state.getString("template-id"));
                }
            }
            for (Map<?, ?> row : yaml.getMapList("history")) try {
                history.add(new HistoryEntry(String.valueOf(row.get("task")), String.valueOf(row.get("cadence")),
                        UUID.fromString(String.valueOf(row.get("uuid"))), String.valueOf(row.get("name")),
                        String.valueOf(row.get("resource")), new BigDecimal(String.valueOf(row.get("amount"))),
                        new BigDecimal(String.valueOf(row.get("score"))), Long.parseLong(String.valueOf(row.get("time")))));
            } catch (RuntimeException ignored) { }
        }
        ensureCurrentPeriods(now);
    }

    private QuestCycle readCycle(String id, String cadence, ConfigurationSection state) {
        Map<String, BigDecimal> targets = readAmounts(state.getConfigurationSection("targets"));
        if (targets.isEmpty()) return null;
        QuestCycle cycle = new QuestCycle(id, state.getString("title", cadence), cadence, targets,
                state.getInt("required-count", targets.size()), decimal(state.get("score-multiplier"), BigDecimal.ONE));
        for (Map<?, ?> row : state.getMapList("records")) try {
            cycle.contribute(UUID.fromString(String.valueOf(row.get("uuid"))), String.valueOf(row.get("resource")),
                    new BigDecimal(String.valueOf(row.get("amount"))), new BigDecimal(String.valueOf(row.get("weight"))));
        } catch (RuntimeException ignored) { }
        return cycle;
    }

    private void validateDifficultyConfig(String cadence) {
        positiveMultiplier("tasks." + cadence + ".score-multiplier", BigDecimal.ONE);
        positiveMultiplier("tasks." + cadence + ".target-multiplier", BigDecimal.ONE);
        ConfigurationSection perResource = plugin.getConfig().getConfigurationSection("tasks." + cadence + ".target-multipliers");
        if (perResource != null) for (String resource : perResource.getKeys(false)) {
            BigDecimal value = decimal(perResource.get(resource), null);
            if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)
                throw new IllegalArgumentException("target-multipliers." + resource + " must be >0 and <=100");
        }
        String templateId = plugin.getConfig().getString("tasks." + cadence + ".automatic-template", "").trim();
        if (!templateId.isEmpty() && dev.bordergate.core.TaskTemplatePool.catalogue().stream().noneMatch(template -> template.id().equals(templateId)))
            throw new IllegalArgumentException("Unknown automatic-template: " + templateId);
        if (plugin.getConfig().contains("tasks." + cadence + ".automatic-required-count")) {
            int required = plugin.getConfig().getInt("tasks." + cadence + ".automatic-required-count", -1);
            if (required < 1 || required > 4) throw new IllegalArgumentException("automatic-required-count must be between 1 and 4");
        }
    }

    private BigDecimal positiveMultiplier(String path, BigDecimal fallback) {
        BigDecimal value = decimal(plugin.getConfig().get(path), fallback);
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException(path + " must be >0 and <=100");
        return value;
    }

    private Map<String, BigDecimal> resourceMultipliers(String cadence) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tasks." + cadence + ".target-multipliers");
        if (section != null) for (String key : section.getKeys(false)) values.put(key, decimal(section.get(key), BigDecimal.ONE));
        return values;
    }

    private QuestCycle createCycle(String cadence, String id) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tasks." + cadence);
        if (!plugin.getConfig().getBoolean("tasks." + cadence + ".enabled", true)) {
            creationFailures.put(id, TaskAvailability.DISABLED);
            return null;
        }
        boolean automatic = plugin.getConfig().getBoolean("automation.enabled", true)
                && !"manual".equalsIgnoreCase(plugin.getConfig().getString("tasks." + cadence + ".source", "auto"));
        try { validateDifficultyConfig(cadence); }
        catch (RuntimeException ex) {
            creationFailures.put(id, automatic ? TaskAvailability.NO_TEMPLATE : TaskAvailability.NO_TARGETS);
            plugin.getLogger().warning("Invalid next-period task configuration for " + cadence + "; no new task created: " + ex.getMessage());
            return null;
        }
        BigDecimal multiplier = positiveMultiplier("tasks." + cadence + ".score-multiplier", BigDecimal.ONE);
        maximumChanges.put(id, plugin.getConfig().getDouble("tasks." + cadence + ".maximum-change-percent", 5));
        automaticBorder.put(id, automatic);
        if (plugin.cooperation() != null) plugin.cooperation().taskStarted(id, System.currentTimeMillis());
        if (automatic) {
            List<String> recent = recentTemplates.computeIfAbsent(cadence, ignored -> new ArrayList<>());
            java.util.Set<String> avoided = plugin.cooperation() == null ? java.util.Set.of() : plugin.cooperation().stalled(cadence);
            String configuredTemplate = plugin.getConfig().getString("tasks." + cadence + ".automatic-template", "").trim();
            var template = configuredTemplate.isEmpty() ? null : dev.bordergate.core.TaskTemplatePool.catalogue().stream()
                    .filter(option -> option.id().equals(configuredTemplate))
                    .filter(option -> option.targets().keySet().stream().allMatch(this::templateResourceAvailable)).findFirst().orElse(null);
            if (template == null && configuredTemplate.isEmpty())
                template = dev.bordergate.core.TaskTemplatePool.select(id, cadence, java.util.Set.copyOf(recent), resource -> templateResourceAvailable(resource) && !avoided.contains(resource));
            if (template == null && configuredTemplate.isEmpty() && !avoided.isEmpty())
                template = dev.bordergate.core.TaskTemplatePool.select(id, cadence, java.util.Set.copyOf(recent), this::templateResourceAvailable);
            if (template == null) {
                creationFailures.put(id, TaskAvailability.NO_TEMPLATE);
                plugin.getLogger().warning("No compatible automatic template for " + id);
                return null;
            }
            templateIds.put(id, template.id());
            recent.add(template.id());
            while (recent.size() > 7) recent.removeFirst();
            String label = switch (cadence) { case "weekly" -> "&6每周远征"; case "monthly" -> "&d每月拓疆"; default -> "&a每日守护"; };
            String title = plugin.getConfig().getString("tasks." + cadence + ".title", label) + " · " + template.title();
            double scale = plugin.cooperation() == null ? 1 : plugin.cooperation().scale(cadence);
            Map<String, BigDecimal> scaled = dev.bordergate.core.TaskDifficultyPolicy.scaleTargets(
                    dev.bordergate.core.AdaptiveTaskPolicy.scaleTargets(template.targets(), scale),
                    positiveMultiplier("tasks." + cadence + ".target-multiplier", BigDecimal.ONE), resourceMultipliers(cadence));
            int configuredRequired = plugin.getConfig().getInt("tasks." + cadence + ".automatic-required-count", template.requiredCount());
            int required = Math.max(1, Math.min(configuredRequired, scaled.size()));
            return new QuestCycle(id, title, cadence, scaled, required, multiplier);
        }
        if (section == null) {
            creationFailures.put(id, TaskAvailability.NO_TARGETS);
            return null;
        }
        Map<String, BigDecimal> configured = readAmounts(section.getConfigurationSection("targets"));
        if (configured.isEmpty()) {
            creationFailures.put(id, TaskAvailability.NO_TARGETS);
            return null;
        }
        int selectCount = section.getInt("select-random", 0);
        if (selectCount > 0 && selectCount < configured.size()) {
            List<Map.Entry<String, BigDecimal>> options = new ArrayList<>(configured.entrySet());
            Collections.shuffle(options, new Random(id.hashCode()));
            Map<String, BigDecimal> selected = new LinkedHashMap<>();
            options.stream().limit(selectCount).forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
            configured = selected;
        }
        int required = Math.max(1, Math.min(section.getInt("required-count", configured.size()), configured.size()));
        return new QuestCycle(id, section.getString("title", cadence), cadence, configured, required, multiplier);
    }

    private boolean templateResourceAvailable(String resource) {
        if (plugin.getConfig().getStringList("automation.excluded-resources").contains(resource)) return false;
        if (resource.startsWith("item:")) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(resource.substring(5));
            return material != null && material.isItem() && !material.isAir();
        }
        return plugin.economy().available(resource);
    }

    private Map<String, BigDecimal> readAmounts(ConfigurationSection section) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (section != null) for (String key : section.getKeys(false)) {
            BigDecimal amount = decimal(section.get(key), null);
            if (amount == null || amount.signum() <= 0) continue;
            if (key.startsWith("playercurrency:") && amount.stripTrailingZeros().scale() > 0) {
                plugin.getLogger().warning("Ignoring non-integer PlayerCurrency target: " + key);
                continue;
            }
            values.put(key, amount);
        }
        return values;
    }

    private BigDecimal decimal(Object input, BigDecimal fallback) {
        if (input instanceof Number number) return new BigDecimal(number.toString());
        if (input instanceof String string) try { return new BigDecimal(string); } catch (NumberFormatException ignored) { }
        return fallback;
    }

    private String periodId(String cadence, long epochMillis) {
        String id = cadence + "-" + schedule.periodDate(cadence, epochMillis);
        NetworkStore store = plugin.networkStore();
        return store != null && plugin.getConfig().getBoolean("network.enabled", false) ? store.serverId() + ":" + id : id;
    }
    private BigDecimal rewardPerPlayer(String cadence) {
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("tasks." + cadence + ".rewards");
        if (rewards == null) return BigDecimal.ZERO;
        return decimal(rewards.get("per-player"), decimal(rewards.get("pool"), BigDecimal.ZERO));
    }

    private String rewardMode(String cadence) {
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("tasks." + cadence + ".rewards");
        return rewards == null ? "fixed" : rewards.getString("mode", "fixed");
    }

    private int rewardScale(String cadence) {
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("tasks." + cadence + ".rewards");
        return rewards == null ? 2 : Math.max(0, Math.min(8, rewards.getInt("amount-scale", 2)));
    }

    private long nextBoundary(String cadence, long epochMillis) {
        return schedule.nextBoundary(cadence, epochMillis);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        recentTemplates.forEach((cadence, ids) -> yaml.set("rotation." + cadence, ids));
        cycles.forEach((id, cycle) -> {
            String root = "cycles." + id;
            yaml.set(root + ".cadence", cycle.cadence()); yaml.set(root + ".title", cycle.title());
            yaml.set(root + ".required-count", cycle.requiredCount()); yaml.set(root + ".score-multiplier", cycle.scoreMultiplier().toPlainString());
            yaml.set(root + ".ends-at", endsAt.getOrDefault(id, 0L)); yaml.set(root + ".settled", settled.getOrDefault(id, false));
            yaml.set(root + ".template-id", templateIds.get(id));
            yaml.set(root + ".automatic-border", automaticBorder.getOrDefault(id, false));
            yaml.set(root + ".maximum-change-percent", maximumChanges.getOrDefault(id, 5.0));
            cycle.targets().forEach((resource, amount) -> yaml.set(root + ".targets." + resource, amount.toPlainString()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (HistoryEntry record : history) if (record.taskId().equals(id)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", record.playerId().toString()); row.put("resource", record.resource());
                row.put("amount", record.amount().toPlainString()); row.put("weight", plugin.resourceWeight(record.resource()).toPlainString());
                rows.add(row);
            }
            yaml.set(root + ".records", rows);
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HistoryEntry record : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", record.taskId()); row.put("cadence", record.cadence()); row.put("uuid", record.playerId().toString());
            row.put("name", record.playerName()); row.put("resource", record.resource()); row.put("amount", record.amount().toPlainString());
            row.put("score", record.score().toPlainString()); row.put("time", record.timestamp()); rows.add(row);
        }
        yaml.set("history", rows);
        File temp = new File(file.getParentFile(), "quests.yml.tmp");
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) throw new IOException("plugin folder unavailable");
            yaml.save(temp);
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ex) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { plugin.getLogger().severe("Cannot save quests.yml: " + ex.getMessage()); }
    }
}
