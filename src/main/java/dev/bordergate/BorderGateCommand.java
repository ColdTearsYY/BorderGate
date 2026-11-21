package dev.bordergate;

import dev.bordergate.core.MenuAccessPolicy;
import java.util.function.Function;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class BorderGateCommand implements CommandExecutor, TabCompleter {
    private final BorderGatePlugin plugin;
    private final BorderGateMenus menus;
    private final Function<String, Player> onlinePlayers;

    BorderGateCommand(BorderGatePlugin plugin, BorderGateMenus menus) {
        this(plugin, menus, Bukkit::getPlayerExact);
    }

    BorderGateCommand(BorderGatePlugin plugin, BorderGateMenus menus, Function<String, Player> onlinePlayers) {
        this.plugin = plugin;
        this.menus = menus;
        this.onlinePlayers = onlinePlayers;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("open")) return openForPlayer(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("view")) {
            if (!sender.hasPermission("bordergate.use")) return deny(sender);
            if (!(sender instanceof Player player)) { sender.sendMessage("任务概览请在游戏内使用 /bg view 查看。"); return true; }
            if (args.length != 1) { send(sender, "用法: /bg view；仅查询任务，提交请点击边界维护员 NPC。"); return true; }
            menus.openOverview(player);
            return true;
        }
        if (sender instanceof Player player && !canUseCommands(player)) {
            send(sender, plugin.colorMessage(plugin.getConfig().getString("messages.command-denied",
                    "&e可用 /bg view 查看日、周、月任务；提交请前往并点击边界维护员 NPC。")));
            return true;
        }
        if (args.length == 0) {
            if (!sender.hasPermission("bordergate.use")) return deny(sender);
            if (sender instanceof Player player) menus.openHome(player);
            else sender.sendMessage("BorderGate GUI is available in-game.");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            if (!sender.hasPermission("bordergate.use")) return deny(sender);
            status(sender);
            return true;
        }
        if (sub.equals("donate")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("只有玩家可以捐献。"); return true; }
            if (!sender.hasPermission("bordergate.use")) return deny(sender);
            if (args.length < 2 || args.length > 4) { sender.sendMessage("用法: /bg donate <resource> [amount] [daily|weekly|monthly]"); return true; }
            String cadence = args.length == 4 ? args[3].toLowerCase(Locale.ROOT) : "daily";
            if (!dev.bordergate.core.TaskSchedule.CADENCES.contains(cadence)) { sender.sendMessage("周期必须是 daily、weekly 或 monthly。"); return true; }
            BigDecimal amount;
            try { amount = args.length >= 3 ? new BigDecimal(args[2]) : BigDecimal.ONE; }
            catch (NumberFormatException ex) { sender.sendMessage("资源数量无效。"); return true; }
            EconomyBridge.Result result = plugin.questService().contribute(player, plugin.questService().currentTaskId(cadence), args[1], amount);
            switch (result) {
                case SUCCESS -> { /* QuestService sends the single contribution receipt. */ }
                case INSUFFICIENT -> sender.sendMessage(ChatColor.RED + "资源不足或支付服务拒绝了交易。");
                case UNAVAILABLE -> sender.sendMessage(ChatColor.RED + "该资源的经济插件未安装、未启用或 API 不可用。");
                case INVALID -> sender.sendMessage(ChatColor.RED + "资源类型/数量无效、任务已结算，或本次投入会超过剩余目标。");
            }
            return true;
        }
        if (List.of("guide", "lands", "towns", "reminders", "cooperation").contains(sub)) {
            if (!sender.hasPermission("bordergate.use")) return deny(sender);
            if (sub.equals("cooperation")) { plugin.cooperation().status().forEach(line -> send(sender, line)); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("此协作操作请在游戏内使用。"); return true; }
            if (!plugin.cooperation().enabled()) { sender.sendMessage("合作引导已关闭。"); return true; }
            synchronized (plugin.questService()) {
                if (sub.equals("guide")) menus.openGuide(player);
                else if (sub.equals("towns")) { if(args.length==1) menus.openTownLeaderboard(player); else sender.sendMessage("用法: /bg towns"); }
                else if (sub.equals("reminders")) sender.sendMessage("个人提醒已" + (plugin.cooperation().toggleReminders(player.getUniqueId()) ? "开启" : "关闭"));
                else if (args.length == 3 && args[1].equalsIgnoreCase("select")) sender.sendMessage(plugin.cooperation().selectLand(player.getUniqueId(), args[2]) ? "已选择协作领地。" : "领地不存在、无权限或选择未保存。");
                else if (args.length == 1) menus.openLands(player);
                else sender.sendMessage("用法: /bg lands [select <领地ID>]；领地与成员由 /lands 管理。");
            }
            return true;
        }

        if (!sender.hasPermission("bordergate.admin")) return deny(sender);
        switch (sub) {
            case "settle" -> {
                String cadence = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "daily";
                if (args.length > 2 || (!dev.bordergate.core.TaskSchedule.CADENCES.contains(cadence) && !cadence.equals("legacy"))) {
                    sender.sendMessage("用法: /bg settle [daily|weekly|monthly|legacy]"); return true;
                }
                if (cadence.equals("legacy")) plugin.settleNow();
                else {
                    String id = plugin.questService().currentTaskId(cadence);
                    var cycle = plugin.questService().cycle(id);
                    if (cycle == null) { sender.sendMessage("此周期暂无任务。"); return true; }
                    plugin.settleQuest(id, cycle.isComplete());
                }
                sender.sendMessage("已提交 " + cadence + " 任务结算。");
            }
            case "reset" -> { plugin.resetGoal(); sender.sendMessage("已建立新的边界维护目标。"); }
            case "initialize" -> {
                if (args.length != 2) { sender.sendMessage("用法: /bg initialize <diameter>"); return true; }
                try {
                    double size = Double.parseDouble(args[1]);
                    if (!Double.isFinite(size) || size <= 0) throw new NumberFormatException();
                    plugin.initializeBorder(size, actual -> send(sender, "初始边界已初始化并保存，当前直径 " + actual + "。"));
                    sender.sendMessage("正在初始化世界边界。");
                } catch (NumberFormatException ex) { sender.sendMessage("直径必须是正数。"); }
            }
            case "setsize" -> {
                if (args.length < 2 || args.length > 3) { sender.sendMessage("用法: /bg setsize <diameter> [seconds]"); return true; }
                try {
                    double size = Double.parseDouble(args[1]);
                    long seconds = args.length == 3 ? Long.parseLong(args[2]) : plugin.transitionSeconds(null);
                    if (!Double.isFinite(size) || size <= 0 || seconds < 0 || seconds > BorderController.MAX_TRANSITION_SECONDS) throw new NumberFormatException();
                    plugin.setBorderSize(size, seconds, actual -> send(sender, "边界目标直径已设为 " + actual + "；" + plugin.borderStatus() + "。"));
                    sender.sendMessage("正在更新边界；原版边界使用原生过渡，ChunkyBorder 直接更新。");
                } catch (NumberFormatException ex) { sender.sendMessage("直径必须是正数，秒数必须是有效的非负整数。"); }
            }
            case "recommend" -> recommend(sender);
            case "reload" -> { plugin.reloadSettings(); sender.sendMessage("配置已重载；已保存任务、目标和期限保持不变，新周期采用新配置。"); }
            default -> sender.sendMessage("用法: /bg <view|status|donate|guide|lands|towns|reminders|cooperation|settle|reset|initialize|setsize|recommend|reload>；控制台代开: bordergate open <在线玩家名>");
        }
        return true;
    }

    private boolean canUseCommands(Player player) {
        return MenuAccessPolicy.canUseCommands(player.hasPermission("bordergate.use"), player.hasPermission("bordergate.command"));
    }

    private boolean openForPlayer(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            send(sender, "此代开命令仅允许服务器控制台执行，玩家、命令方块和代理执行者不可使用。");
            return true;
        }
        if (args.length != 2) { sender.sendMessage("用法: bordergate open <在线玩家名>"); return true; }
        Player target = onlinePlayers.apply(args[1]);
        if (target == null || !target.isOnline()) { sender.sendMessage("代开失败：找不到该名称的在线玩家。"); return true; }
        if (!target.hasPermission("bordergate.use")) { sender.sendMessage("代开失败：目标玩家没有 bordergate.use 权限。"); return true; }
        String targetName = target.getName();
        Runnable cancelled = () -> sender.sendMessage("代开取消：目标玩家已离线，玩家调度器已退出。");
        var scheduled = target.getScheduler().run(plugin, task -> {
            if (!target.isOnline()) { sender.sendMessage("代开取消：目标玩家已经离线。"); return; }
            if (!target.hasPermission("bordergate.use")) { sender.sendMessage("代开取消：目标玩家的使用权限已撤销。"); return; }
            menus.openHome(target);
            sender.sendMessage("已为 " + targetName + " 打开 BorderGate 菜单。");
        }, cancelled);
        if (scheduled == null) cancelled.run();
        return true;
    }

    private void status(CommandSender sender) {
        List<String> taskLines = plugin.questService().statusLines(System.currentTimeMillis());
        send(sender, ChatColor.GOLD + "[BorderGate] " + ChatColor.WHITE + plugin.managedWorldName()
                + " | 当前边界直径 " + plugin.trackedSize());
        send(sender, "边界状态: " + plugin.borderStatus());
        send(sender, "边界下限: " + plugin.minimumSize() + " | 当前总上限: " + plugin.effectiveMaximum());
        for (String line : taskLines) send(sender, plugin.colorMessage(line));
        send(sender, "上次结算: " + plugin.lastResult());
    }

    private void recommend(CommandSender sender) {
        BigDecimal total = plugin.economy().vaultTotal();
        if (total == null) { sender.sendMessage("无法统计 Vault 经济：Vault 或 Economy provider 不可用。"); return; }
        double baseline = Math.max(1.0, plugin.getConfig().getDouble("economy-recommendation.baseline-vault-balance", 1_000_000));
        double min = Math.max(0.0, plugin.getConfig().getDouble("economy-recommendation.minimum-multiplier", 1));
        double max = Math.max(min, plugin.getConfig().getDouble("economy-recommendation.maximum-multiplier", 20));
        double multiplier = Math.max(min, Math.min(max, Math.sqrt(total.doubleValue() / baseline)));
        BigDecimal baseTarget = plugin.targets().getOrDefault("vault", BigDecimal.ZERO);
        BigDecimal suggested = baseTarget.multiply(BigDecimal.valueOf(multiplier));
        sender.sendMessage("Vault 账户余额合计: " + total.stripTrailingZeros().toPlainString());
        sender.sendMessage("建议系数: clamp(sqrt(余额合计 / " + baseline + "), " + min + ", " + max + ") = " + multiplier);
        sender.sendMessage("以本周期 Vault 目标为基数，建议目标: " + suggested.stripTrailingZeros().toPlainString()
                + "。仅供参考，不会自动改价或扣款。");
    }

    private boolean deny(CommandSender sender) { sender.sendMessage(ChatColor.RED + "你没有执行此命令的权限。"); return true; }

    private void send(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
        } else sender.sendMessage(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player player && !canUseCommands(player)) {
            return player.hasPermission("bordergate.use") && args.length == 1 && "view".startsWith(args[0].toLowerCase(Locale.ROOT))
                    ? List.of("view") : List.of();
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("view")) return List.of();
        if (args.length > 0 && args[0].equalsIgnoreCase("open")) return List.of();
        if (args.length == 1) {
            List<String> values = sender.hasPermission("bordergate.admin")
                    ? List.of("view", "status", "donate", "guide", "lands", "towns", "reminders", "cooperation", "settle", "reset", "initialize", "setsize", "recommend", "reload")
                    : List.of("view", "status", "donate", "guide", "lands", "towns", "reminders", "cooperation");
            if (sender instanceof ConsoleCommandSender && "open".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                values = new ArrayList<>(values);
                values.add("open");
            }
            return values.stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lands")) return List.of("select").stream().filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("lands") && args[1].equalsIgnoreCase("select") && sender instanceof Player player && sender.hasPermission("bordergate.use")) return plugin.cooperation().playerLands(player.getUniqueId()).stream().map(dev.bordergate.core.LandAffiliation::id).filter(id -> id.startsWith(args[2])).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("donate")) {
            var cycle = plugin.questService().cycle(plugin.questService().currentTaskId("daily"));
            List<String> resources = cycle == null ? List.of() : new ArrayList<>(cycle.targets().keySet());
            return resources.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if ((args.length == 2 && args[0].equalsIgnoreCase("settle") && sender.hasPermission("bordergate.admin"))
                || (args.length == 4 && args[0].equalsIgnoreCase("donate"))) {
            List<String> values = args[0].equalsIgnoreCase("settle")
                    ? List.of("daily", "weekly", "monthly", "legacy") : dev.bordergate.core.TaskSchedule.CADENCES;
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
