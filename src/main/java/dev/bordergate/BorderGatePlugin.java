package dev.bordergate;

import dev.bordergate.core.BorderMath;
import dev.bordergate.core.SettlementAnnouncements;
import dev.bordergate.core.GoalLedger;
import dev.bordergate.core.ContributionService;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class BorderGatePlugin extends JavaPlugin {
    private final Object stateLock = new Object();
    private final SettlementAnnouncements settlementAnnouncements = new SettlementAnnouncements();
    private EconomyBridge economy;
    private QuestService questService;
    private BorderGateMenus menus;
    private NetworkStore networkStore;
    private GoalLedger ledger;
    private long endsAtMillis;
    private String lastResult = "尚无维护记录";
    private volatile double trackedSize;
    private volatile double centerX;
    private volatile double centerZ;
    private volatile boolean initialized;
    private File stateFile;
    private BorderController borders;
    private volatile String borderError = "边界尚未就绪";
    private volatile double borderTarget;
    private volatile long borderTransitionEndsAt;
    private String transitionWorld = "";
    private String transitionBackend = "";
    private final Map<String, Boolean> appliedSettlements = new LinkedHashMap<>();
    private volatile double maximumMultiplier = 1.0;
    private CooperationService cooperation;
    CooperationService cooperation() { return cooperation; }


    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "data.yml");
        economy = new EconomyBridge(this);
        loadState();
        if (getCommand("bordergate") == null) {
            getLogger().severe("bordergate command is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getConfig().getBoolean("network.enabled", false)) networkStore = new NetworkStore(this);
        cooperation = new CooperationService(this);
        questService = new QuestService(this);
        cooperation.migrateHistory();
        menus = new BorderGateMenus(this);
        getServer().getPluginManager().registerEvents(menus, this);
        getCommand("bordergate").setExecutor(new BorderGateCommand(this, menus));
        getCommand("bordergate").setTabCompleter(new BorderGateCommand(this, menus));
        reportOptionalPlugins();
        borders = new BorderController(this);
        Bukkit.getGlobalRegionScheduler().run(this, task -> initializeWorld());
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> { questService.settleExpired(); cooperation.tick(); }, 20L, 1200L);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> refreshBorder(), 20L, 20L);
        getLogger().info("BorderGate enabled. World=" + getConfig().getString("world", "world"));
    }

    @Override
    public void onDisable() {
        if (cooperation != null) cooperation.save();
        synchronized (stateLock) { saveStateLocked(); }
        if (networkStore != null) networkStore.close();
    }

    private void initializeWorld() {
        World world = managedWorld();
        if (world == null) { borderFailed("管理世界未加载"); return; }
        try {
            var existing = borders.read(world);
            if (borders.usesChunkyBorder()) {
                if (existing.isEmpty()) throw new IllegalStateException("ChunkyBorder 尚未设置该世界边界，请先设置边界或使用 /bg initialize");
                borderTransitionEndsAt = 0;
            } else if (!initialized && existing.orElseThrow().diameter() >= world.getWorldBorder().getMaxSize() - 1) {
                borders.initialize(world, clamp(getConfig().getDouble("border.initial-size", 3000)),
                        getConfig().getDouble("border.initial-center-x", 0), getConfig().getDouble("border.initial-center-z", 0), "square");
                borderTransitionEndsAt = 0;
            } else if (borderTransitionEndsAt > 0 && transitionBackend.equals("vanilla")
                    && transitionWorld.equals(world.getName()) && Double.isFinite(borderTarget) && borderTarget > 0) {
                long remaining = Math.max(0, (borderTransitionEndsAt - System.currentTimeMillis() + 999) / 1000);
                borders.setDiameter(world, borderTarget, remaining);
            } else {
                borderTransitionEndsAt = 0;
            }
            updateSnapshot(borders.read(world).orElseThrow());
            initialized = true;
            synchronized (stateLock) {
                if (ledger == null || ledger.targets().isEmpty()) createNewGoalLocked();
                saveStateLocked();
            }
            syncChunkySelection(world);
            getLogger().info("Border source=" + borderBackend() + "; current diameter=" + trackedSize);
        } catch (RuntimeException ex) { borderFailed(ex.getMessage()); }
    }

    private void reportOptionalPlugins() {
        for (String plugin : List.of("Vault", "PlayerPoints", "PlayerCurrency", "Chunky", "ChunkyBorder")) {
            if (!Bukkit.getPluginManager().isPluginEnabled(plugin)) {
                getLogger().info(plugin + " not detected; its optional BorderGate integration is inactive.");
            }
        }
    }

    private void checkDeadline() {
        synchronized (stateLock) {
            if (ledger != null && !ledger.targets().isEmpty() && System.currentTimeMillis() >= endsAtMillis) {
                settleLocked();
            }
        }
    }

    void settleNow() {
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            synchronized (stateLock) { settleLocked(); }
        });
    }

    private void settleLocked() {
        if (ledger == null || ledger.targets().isEmpty()) return;
        World world = managedWorld();
        if (world == null) return;
        boolean met = ledger.isComplete();
        double current = adjustmentBase(world);
        if (!Double.isFinite(current)) return;
        double next = BorderMath.nextDiameter(current,
                minimumSize(), effectiveMaximum(),
                getConfig().getDouble("border.expand-by", 256.0),
                getConfig().getDouble("border.shrink-by", 128.0), met);
        if (!applyDiameter(world, next, transitionSeconds(null))) return;
        lastResult = met ? "维护达标，边界扩展" : "维护未达标，边界收缩";
        createNewGoalLocked();
        saveStateLocked();
        getLogger().info(lastResult + "；目标边界直径 " + next + "；下一周期目标 " + formatTargets(ledger.targets()));
    }

    QuestService questService() { return questService; }
    NetworkStore networkStore() { return networkStore; }

    String colorMessage(String message) { return org.bukkit.ChatColor.translateAlternateColorCodes('&', message); }

    BigDecimal resourceWeight(String resource) {
        Object configured = getConfig().get("resources.weights." + resource);
        if (configured instanceof Number number) {
            BigDecimal value = new BigDecimal(number.toString());
            if (value.signum() > 0) return value;
        }
        return BigDecimal.ONE;
    }

    void onQuestProgress(String taskId) {
        getLogger().info("Task " + taskId + " progress updated.");
    }

    void settleQuest(String taskId, boolean requestedSuccess) {
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            synchronized (questService) {
                var cycle = questService.cycle(taskId);
                if (cycle == null || questService.isSettled(taskId)) return;
                World world = managedWorld();
                if (world == null) { borderFailed("管理世界未加载"); return; }
                NetworkStore store = networkStore;
                if (store != null && getConfig().getBoolean("network.enabled", false)
                        && !taskId.startsWith(store.serverId() + ":")) return;
                boolean success = appliedSettlements.getOrDefault(taskId, requestedSuccess && cycle.isComplete());
                String cadence = cycle.cadence();
                try {
                    double current = adjustmentBase(world);
                    if (!Double.isFinite(current)) return;
                    if (!appliedSettlements.containsKey(taskId)) {
                        if (cadence.equals("monthly")) {
                            double maximum = dev.bordergate.core.BorderBalancePolicy.nextMaximum(minimumSize(), effectiveMaximum(),
                                    questService.maximumChangePercent(taskId), success,
                                    borders.usesChunkyBorder() ? Double.MAX_VALUE : world.getWorldBorder().getMaxSize());
                            synchronized (stateLock) {
                                double previous = maximumMultiplier;
                                maximumMultiplier = maximum / getConfig().getDouble("border.maximum-size", 12000);
                                appliedSettlements.put(taskId, success);
                                if (!saveStateLocked()) {
                                    maximumMultiplier = previous;
                                    appliedSettlements.remove(taskId);
                                    borderFailed("月上限状态保存失败，结算暂停");
                                    return;
                                }
                            }
                        } else {
                            var delta = taskAdjustment(cadence, current, questService.automaticBorder(taskId));
                            double next = BorderMath.nextDiameter(current, minimumSize(), effectiveMaximum(), delta.expand(), delta.shrink(), success);
                            if (!applyDiameter(world, next, transitionSeconds(cadence))) return;
                            synchronized (stateLock) { appliedSettlements.put(taskId, success); saveStateLocked(); }
                        }
                    }
                    // A saved monthly cap receipt may precede the backend resize; retries finish it without reapplying the percentage.
                    if (cadence.equals("monthly") && current > effectiveMaximum()
                            && !applyDiameter(world, effectiveMaximum(), transitionSeconds(cadence))) return;
                } catch (IllegalArgumentException ex) { borderFailed("任务边界策略无效: " + ex.getMessage()); return; }
                if (store != null && store.available()) {
                    if (!store.markSettled(taskId, success)) return;
                    if (success && !store.claimRewards(taskId)) return;
                }
                questService.settle(taskId);
                if (cooperation != null) cooperation.settled(taskId, success);
                lastResult = cadence.equals("monthly")
                        ? "monthly任务" + (success ? "完成，上限提高至 " : "未完成，上限降低至 ") + displaySize(effectiveMaximum())
                        : success ? cadence + "任务完成，边界扩张" : cadence + "任务未完成，边界收缩";
                synchronized (stateLock) {
                    if (!cadence.equals("monthly")) appliedSettlements.remove(taskId);
                    saveStateLocked();
                }
                if (success) questService.applyRewards(taskId);
                getLogger().info(lastResult + "；任务=" + taskId + "；完成=" + cycle.completionPercent().toPlainString() + "%");
                announceSettlement(taskId, cadence, success);
            }
        });
    }

    private void announceSettlement(String taskId, String cadence, boolean success) {
        String localServer = networkStore == null ? getConfig().getString("network.server-id", "server-1") : networkStore.serverId();
        int separator = taskId.indexOf(':');
        String owner = separator < 0 ? localServer : taskId.substring(0, separator);
        settlementAnnouncements.announce(getConfig().getBoolean("announcements.settlement.enabled", false),
                localServer, owner, taskId, cadence, success, trackedSize,
                getConfig().getString("announcements.settlement.success", SettlementAnnouncements.DEFAULT_SUCCESS),
                getConfig().getString("announcements.settlement.failure", SettlementAnnouncements.DEFAULT_FAILURE), message -> {
                    String rendered = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
                    for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                        player.getScheduler().run(this, scheduled -> player.sendMessage(rendered), null);
                    }
                });
    }

    void adjustBorder(double delta) {
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            World world = managedWorld();
            if (world == null) { borderFailed("管理世界未加载"); return; }
            double current = adjustmentBase(world);
            if (!Double.isFinite(current)) return;
            applyDiameter(world, clamp(current + delta), transitionSeconds(null));
        });
    }

    void resetGoal() {
        synchronized (stateLock) { createNewGoalLocked(); saveStateLocked(); }
    }

    private void createNewGoalLocked() {
        Map<String, BigDecimal> targets = configuredTargets();
        ledger = GoalLedger.empty(targets);
        endsAtMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(Math.max(1, getConfig().getLong("cycle.interval-hours", 24)));
    }

    private Map<String, BigDecimal> configuredTargets() {
        Map<String, BigDecimal> configured = new LinkedHashMap<>();
        String mode = getConfig().getString("goal.mode", "fixed");
        if ("random".equalsIgnoreCase(mode)) {
            List<String> pool = getConfig().getStringList("goal.random-pool").stream().filter(economy::available).toList();
            if (pool.isEmpty()) return configured;
            String selected = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            long min = Math.max(1, getConfig().getLong("goal.random-minimum", 1));
            long max = Math.max(min, getConfig().getLong("goal.random-maximum", min));
            long amount = ThreadLocalRandom.current().nextLong(min, max == Long.MAX_VALUE ? max : max + 1);
            configured.put(selected, BigDecimal.valueOf(amount));
            return configured;
        }
        ConfigurationSection section = getConfig().getConfigurationSection("goal.fixed");
        if (section == null) return configured;
        for (String resource : section.getKeys(false)) {
            BigDecimal value = decimal(section.get(resource));
            if (value == null || value.signum() <= 0) continue;
            if (!economy.available(resource)) {
                if (getConfig().getBoolean("integrations.allow-downgrade-when-provider-missing", true)) {
                    getLogger().warning("Omitting unavailable goal resource " + resource + " for this cycle.");
                    continue;
                }
                return Map.of();
            }
            configured.put(resource, value);
        }
        return configured;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String string) {
            try { return new BigDecimal(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    EconomyBridge.Result contribute(org.bukkit.entity.Player player, String resource, BigDecimal amount) {
        synchronized (stateLock) {
            AtomicReference<EconomyBridge.Result> paymentResult = new AtomicReference<>(EconomyBridge.Result.INVALID);
            ContributionService.Result result = ContributionService.contribute(ledger, resource, amount, (key, value) -> {
                EconomyBridge.Result charged = economy.charge(player, key, value);
                paymentResult.set(charged);
                return charged == EconomyBridge.Result.SUCCESS;
            });
            if (result == ContributionService.Result.INVALID) return EconomyBridge.Result.INVALID;
            if (result == ContributionService.Result.PAYMENT_FAILED) return paymentResult.get();
            saveStateLocked();
            return EconomyBridge.Result.SUCCESS;
        }
    }

    Map<String, BigDecimal> targets() {
        synchronized (stateLock) { return ledger == null ? Map.of() : new LinkedHashMap<>(ledger.targets()); }
    }

    Map<String, BigDecimal> contributed() {
        synchronized (stateLock) { return ledger == null ? Map.of() : new LinkedHashMap<>(ledger.contributed()); }
    }

    long endsAtMillis() { synchronized (stateLock) { return endsAtMillis; } }
    String lastResult() { return lastResult; }
    double trackedSize() { return trackedSize; }
    String managedWorldName() { return getConfig().getString("world", "world"); }
    EconomyBridge economy() { return economy; }

    void initializeBorder(double requested, java.util.function.Consumer<Double> completed) {
        double size = clamp(requested);
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            World world = managedWorld();
            if (world == null) { borderFailed("管理世界未加载"); return; }
            try {
                var existing = borders.read(world);
                double x = borders.usesChunkyBorder() && existing.isPresent() ? existing.get().centerX()
                        : getConfig().getDouble("border.initial-center-x", 0);
                double z = borders.usesChunkyBorder() && existing.isPresent() ? existing.get().centerZ()
                        : getConfig().getDouble("border.initial-center-z", 0);
                borders.initialize(world, size, x, z, getConfig().getString("integrations.chunky-border-initial-shape", "square"));
                borderTransitionEndsAt = 0;
                updateSnapshot(borders.read(world).orElseThrow());
                initialized = true;
                getConfig().set("border.initial-size", size);
                saveConfig();
                syncChunkySelection(world);
                synchronized (stateLock) { saveStateLocked(); }
                if (completed != null) completed.accept(size);
            } catch (RuntimeException ex) { borderFailed(ex.getMessage()); }
        });
    }

    void setBorderSize(double requested, java.util.function.Consumer<Double> completed) {
        setBorderSize(requested, transitionSeconds(null), completed);
    }

    void setBorderSize(double requested, long seconds, java.util.function.Consumer<Double> completed) {
        double size = clamp(requested);
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            World world = managedWorld();
            if (world == null) { borderFailed("管理世界未加载"); return; }
            if (applyDiameter(world, size, seconds) && completed != null) completed.accept(size);
        });
    }

    void reloadSettings() {
        reloadConfig();
        if (questService != null) questService.reload();
        if (cooperation != null) cooperation.refreshClaims();
        Bukkit.getGlobalRegionScheduler().run(this, task -> initializeWorld());
    }

    double minimumSize() { return getConfig().getDouble("border.minimum-size", 1000); }
    double effectiveMaximum() {
        double configured = getConfig().getDouble("border.maximum-size", 12000);
        double effective = configured * maximumMultiplier;
        return Math.max(minimumSize(), Double.isFinite(effective) ? effective : configured);
    }

    private double clamp(double size) {
        if (!Double.isFinite(size) || size <= 0) throw new IllegalArgumentException("Border diameter must be positive and finite");
        return Math.max(minimumSize(), Math.min(effectiveMaximum(), size));
    }

    private dev.bordergate.core.BorderBalancePolicy.Adjustment taskAdjustment(String cadence, double size, boolean automatic) {
        if (automatic) return dev.bordergate.core.BorderBalancePolicy.adjustments(size, minimumSize(), effectiveMaximum(), cadence,
                getConfig().getDouble("automation.daily-range-percent", 1), getConfig().getDouble("automation.weekly-range-percent", 4));
        return new dev.bordergate.core.BorderBalancePolicy.Adjustment(
                getConfig().getDouble("tasks." + cadence + ".border-expand", cadence.equals("weekly") ? 512 : 128),
                getConfig().getDouble("tasks." + cadence + ".border-shrink", cadence.equals("weekly") ? 256 : 64));
    }

    String taskEffect(String taskId) {
        var cycle = questService.cycle(taskId);
        if (cycle == null) return "暂无任务";
        if (networkStore != null && getConfig().getBoolean("network.enabled", false) && !taskId.startsWith(networkStore.serverId() + ":")) {
            return "边界效果由任务所属服务器独立执行";
        }
        if (cycle.cadence().equals("monthly")) return "总上限: " + displaySize(effectiveMaximum())
                + " | 月任务完成 +" + displaySize(questService.maximumChangePercent(taskId)) + "% / 未完成 -"
                + displaySize(questService.maximumChangePercent(taskId)) + "%（不低于下限）";
        double base = borderTransitionEndsAt > 0 ? borderTarget : trackedSize;
        if (!Double.isFinite(base) || base <= 0) return "边界尚未就绪，暂不能预估任务效果";
        try {
            var delta = taskAdjustment(cycle.cadence(), base, questService.automaticBorder(taskId));
            return (questService.automaticBorder(taskId) ? "自动曲线" : "手动策略") + " | 当前预计扩张 +" + displaySize(delta.expand())
                    + " / 收缩 -" + displaySize(delta.shrink()) + "格（以结算时边界为准）";
        } catch (IllegalArgumentException ex) { return "边界策略配置无效: " + ex.getMessage(); }
    }

    private String displaySize(double value) {
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private World managedWorld() { return Bukkit.getWorld(managedWorldName()); }

    private void syncChunkySelection(World world) {
        if (borders == null || borders.usesChunkyBorder() || borderTransitionEndsAt > 0
                || !getConfig().getBoolean("integrations.chunky", true)
                || !Bukkit.getPluginManager().isPluginEnabled("Chunky")) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky world " + world.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky worldborder");
    }

    void onNativeBorderOverride() {
        borderTransitionEndsAt = 0;
        synchronized (stateLock) { saveStateLocked(); }
    }

    long transitionSeconds(String cadence) {
        long fallback = getConfig().getLong("border.transition-seconds", 30);
        long configured = cadence == null ? fallback : getConfig().getLong("tasks." + cadence + ".border-transition-seconds", fallback);
        return Math.max(0, Math.min(configured, BorderController.MAX_TRANSITION_SECONDS));
    }

    String borderBackend() { return borders != null && borders.usesChunkyBorder() ? "ChunkyBorder" : "vanilla"; }

    String borderStatus() {
        if (borderError != null) return borderBackend() + " | 已暂停: " + borderError;
        if (borderTransitionEndsAt > 0) return borderBackend() + " | 目标直径 " + borderTarget
                + " | 过渡剩余约 " + Math.max(0, (borderTransitionEndsAt - System.currentTimeMillis() + 999) / 1000) + " 秒";
        return borderBackend() + " | 当前直径 " + trackedSize;
    }

    private void updateSnapshot(BorderController.Snapshot snapshot) {
        trackedSize = snapshot.diameter();
        centerX = snapshot.centerX();
        centerZ = snapshot.centerZ();
        if (borderTransitionEndsAt == 0) borderTarget = trackedSize;
        borderError = null;
    }

    private void borderFailed(String message) {
        String error = message == null ? "边界接口不可用" : message;
        if (!error.equals(borderError)) getLogger().severe("Border changes suspended: " + error);
        borderError = error;
    }

    private double adjustmentBase(World world) {
        try {
            var snapshot = borders.read(world).orElseThrow(() -> new IllegalStateException("ChunkyBorder 尚未设置该世界边界"));
            updateSnapshot(snapshot);
            return !borders.usesChunkyBorder() && borderTransitionEndsAt > 0 && transitionWorld.equals(world.getName())
                    ? borderTarget : snapshot.diameter();
        } catch (RuntimeException ex) { borderFailed(ex.getMessage()); return Double.NaN; }
    }

    private boolean applyDiameter(World world, double diameter, long seconds) {
        try {
            long duration = Math.max(0, Math.min(seconds, BorderController.MAX_TRANSITION_SECONDS));
            borders.setDiameter(world, diameter, duration);
            borderTransitionEndsAt = !borders.usesChunkyBorder() && duration > 0
                    ? System.currentTimeMillis() + duration * 1000 : 0;
            borderTarget = diameter;
            transitionWorld = world.getName();
            transitionBackend = borderBackend();
            updateSnapshot(borders.read(world).orElseThrow());
            initialized = true;
            synchronized (stateLock) { saveStateLocked(); }
            syncChunkySelection(world);
            getLogger().info("Border update: source=" + borderBackend() + ", current=" + trackedSize + ", target=" + diameter
                    + ", transition-seconds=" + (borders.usesChunkyBorder() ? 0 : duration));
            return true;
        } catch (RuntimeException ex) { borderFailed(ex.getMessage()); return false; }
    }

    private void refreshBorder() {
        World world = managedWorld();
        if (world == null) { borderFailed("管理世界未加载"); return; }
        if (borders == null) return;
        try {
            updateSnapshot(borders.read(world).orElseThrow(() -> new IllegalStateException("ChunkyBorder 尚未设置该世界边界")));
            if (borderTransitionEndsAt > 0 && Math.abs(trackedSize - borderTarget) < 0.01) {
                borderTransitionEndsAt = 0;
                synchronized (stateLock) { saveStateLocked(); }
                syncChunkySelection(world);
            }
        } catch (RuntimeException ex) { borderFailed(ex.getMessage()); }
    }

    private String formatTargets(Map<String, BigDecimal> values) {
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().stripTrailingZeros().toPlainString())
                .reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        initialized = yaml.getBoolean("initialized", false);
        trackedSize = yaml.getDouble("border.size", 0);
        centerX = yaml.getDouble("border.center-x", 0);
        centerZ = yaml.getDouble("border.center-z", 0);
        borderTarget = yaml.getDouble("border.target-size", trackedSize);
        borderTransitionEndsAt = yaml.getLong("border.transition-ends-at", 0);
        transitionWorld = yaml.getString("border.transition-world", "");
        transitionBackend = yaml.getString("border.backend", "");
        double restoredMultiplier = yaml.getDouble("border.maximum-multiplier", 1);
        if (Double.isFinite(restoredMultiplier) && restoredMultiplier > 0) maximumMultiplier = restoredMultiplier;
        else getLogger().warning("Invalid saved maximum multiplier; using configured maximum");
        ConfigurationSection applied = yaml.getConfigurationSection("border.applied-settlements");
        if (applied != null) applied.getKeys(false).forEach(id -> appliedSettlements.put(id, applied.getBoolean(id)));
        endsAtMillis = yaml.getLong("cycle.ends-at", 0);
        lastResult = yaml.getString("last-result", lastResult);
        Map<String, BigDecimal> targets = readAmounts(yaml.getConfigurationSection("cycle.targets"));
        Map<String, BigDecimal> contributions = readAmounts(yaml.getConfigurationSection("cycle.contributed"));
        if (!targets.isEmpty()) ledger = new GoalLedger(targets, contributions);
    }

    private Map<String, BigDecimal> readAmounts(ConfigurationSection section) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (section == null) return values;
        for (String key : section.getKeys(false)) {
            BigDecimal value = decimal(section.get(key));
            if (value != null && value.signum() > 0) values.put(key, value);
        }
        return values;
    }

    private boolean saveStateLocked() {
        if (stateFile == null) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("initialized", initialized);
        yaml.set("border.size", trackedSize);
        yaml.set("border.center-x", centerX);
        yaml.set("border.center-z", centerZ);
        yaml.set("border.target-size", borderTarget);
        yaml.set("border.transition-ends-at", borderTransitionEndsAt);
        yaml.set("border.transition-world", transitionWorld);
        yaml.set("border.backend", borderBackend());
        yaml.set("border.maximum-multiplier", maximumMultiplier);
        yaml.set("border.effective-maximum", effectiveMaximum());
        appliedSettlements.forEach((id, success) -> yaml.set("border.applied-settlements." + id, success));
        yaml.set("last-result", lastResult);
        yaml.set("cycle.ends-at", endsAtMillis);
        if (ledger != null) {
            ledger.targets().forEach((key, value) -> yaml.set("cycle.targets." + key, value.toPlainString()));
            ledger.contributed().forEach((key, value) -> yaml.set("cycle.contributed." + key, value.toPlainString()));
        }
        File temporary = new File(getDataFolder(), "data.yml.tmp");
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IOException("Could not create plugin data folder");
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            getLogger().severe("Could not save BorderGate state: " + ex.getMessage());
            return false;
        }
    }
}
