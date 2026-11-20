package dev.bordergate;

import dev.bordergate.core.QuestCycle;
import dev.bordergate.core.TaskOverview;
import dev.bordergate.core.LandAffiliation;
import dev.bordergate.core.TaskAvailability;
import dev.bordergate.core.MenuAccessPolicy;
import net.kyori.adventure.text.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class BorderGateMenus implements Listener {
    private enum View { OVERVIEW, HOME, TASK, HISTORY, ADMIN, NETWORK, GUIDE, CLAIMS, LANDS, TOWNS }
    private static final int[] RESOURCE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private static final class Holder implements InventoryHolder {
        private final View view;
        private final String taskId;
        private final int page;
        private final Map<Integer,String> actions = new LinkedHashMap<>();
        private final Map<Integer,BigDecimal> amounts = new LinkedHashMap<>();
        private Inventory inventory;
        private Holder(View view, String taskId) { this(view, taskId, 0); }
        private Holder(View view, String taskId, int page) { this.view=view; this.taskId=taskId; this.page=Math.max(0,page); }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final BorderGatePlugin plugin;
    BorderGateMenus(BorderGatePlugin plugin) { this.plugin=plugin; }
    private boolean canAutoOpen(Player player) {
        return MenuAccessPolicy.canAutoOpen(plugin.getConfig().getBoolean("gui.open-on-join",false),
                player.hasPermission("bordergate.use"),player.hasPermission("bordergate.command"));
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player=event.getPlayer();
        plugin.cooperation().onJoin(player);
        if (canAutoOpen(player)) player.getScheduler().runDelayed(plugin, task -> {
            if(player.isOnline() && canAutoOpen(player)) openHome(player);
        },null,40L);
    }
    void openOverview(Player player) { openOverview(player, 0); }
    private void openOverview(Player player, int requestedPage) {
        if (!player.hasPermission("bordergate.use")) return;
        var snapshots = plugin.questService().overview(System.currentTimeMillis());
        int pages = snapshots.stream().mapToInt(TaskOverview::pages).max().orElse(1);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Holder holder = new Holder(View.OVERVIEW, null, page);
        holder.inventory = Bukkit.createInventory(holder, 54, text("gui.overview-title", "&8日 / 周 / 月任务 · 只读概览"));
        Material[] icons = {Material.COMPASS, Material.CLOCK, Material.NETHER_STAR};
        String[] colors = {"&a", "&6", "&d"};
        for (int column = 0; column < snapshots.size(); column++) {
            TaskOverview snapshot = snapshots.get(column);
            List<String> summary = new ArrayList<>();
            if (snapshot.resources().isEmpty()) summary.add(missingTaskMessage(snapshot.cadence()));
            else {
                summary.add("&f" + snapshot.title());
                summary.add("&7状态: &f" + snapshot.status());
                summary.add("&7达标资源: &f" + snapshot.completed() + "/" + snapshot.required() + " 种（共 " + snapshot.resources().size() + " 种可选）");
                summary.add("&7完成率: &f" + num(snapshot.percent().setScale(0, java.math.RoundingMode.DOWN)) + "%");
                summary.add("&7截止: &f" + plugin.questService().formatDeadline(snapshot.deadline()));
                summary.add("&7不要求填满全部可选资源");
            }
            summary.add("&7此列资源仅供查看，不能投入或认领");
            set(holder.inventory, 1 + column * 3, icons[column], colors[column] + TaskAvailability.cadenceLabel(snapshot.cadence()), summary);
            List<TaskOverview.Resource> resources = snapshot.page(page);
            for (int i = 0; i < resources.size(); i++) {
                var resource = resources.get(i);
                set(holder.inventory, TaskOverview.resourceSlot(column, i), material(resource.id()),
                        ResourceDisplay.format("&f{resource}", resourceName(resource.id())),
                        List.of("&7已投入: &f" + num(resource.contributed()) + "/" + num(resource.target()),
                                "&7还缺: &f" + num(resource.remaining()),
                                resource.remaining().signum() == 0 ? "&a此项已达标" : "&e此项尚未达标",
                                "&7仅供查询；提交请点击边界维护员 NPC").stream().map(ResourceDisplay::text).toList());
            }
        }
        set(holder.inventory, 46, Material.MAP, "&b本服边界", List.of("&7当前直径: &f" + plugin.trackedSize(), "&7当前总上限: &f" + plugin.effectiveMaximum()));
        set(holder.inventory, 47, Material.VILLAGER_SPAWN_EGG, "&e提交请找边界维护员", List.of("&7前往并点击边界维护员 NPC", "&7此菜单不能提交、认领或修改数据"));
        set(holder.inventory, 48, Material.BARRIER, "&c关闭", List.of());
        set(holder.inventory, 49, Material.CLOCK, "&a刷新进度", List.of("&7点击读取最新任务进度", "&7第 " + (page + 1) + "/" + pages + " 页"));
        pagination(holder, pages, 1, 45, 53);
        player.openInventory(holder.inventory);
    }

    void openHome(Player player) {
        if (!player.hasPermission("bordergate.use")) return;
        plugin.questService().refreshNetworkTasks();
        Holder holder=new Holder(View.HOME,null);
        holder.inventory=Bukkit.createInventory(holder,27,text("gui.home-title","&8边界稳定器"));
        long now=System.currentTimeMillis();
        homeTask(holder,10,Material.COMPASS,"daily",text("gui.daily-name","&a每日守护"),now);
        homeTask(holder,12,Material.CLOCK,"weekly",text("gui.weekly-name","&6每周远征"),now);
        homeTask(holder,14,Material.NETHER_STAR,"monthly",text("gui.monthly-name","&d每月拓疆"),now);
        set(holder.inventory,16,Material.WRITABLE_BOOK,text("gui.history-name","&b守护者榜单"),List.of(text("gui.history-lore","&7查看贡献记录与个人排名")));
        if (plugin.getConfig().getBoolean("network.enabled",false)) set(holder.inventory,18,Material.ENDER_EYE,text("gui.network-name","&d全服任务"),List.of(text("gui.network-lore","&7查看并提交其他服务器的任务")));
        if (player.hasPermission("bordergate.admin")) set(holder.inventory,22,Material.REDSTONE,text("gui.admin-name","&c管理控制台"),List.of(text("gui.admin-lore","&7结算、重载与边界调整")));
        if (plugin.cooperation().enabled()) {
            String id=plugin.cooperation().recommendedTask(player);
            List<Component> lore=new ArrayList<>();
            if (id!=null) {
                holder.actions.put(4,id); QuestCycle cycle=plugin.questService().cycle(id);
                lore.add(ResourceDisplay.text("&7推荐周期: "+TaskAvailability.cadenceLabel(cycle.cadence())));
                lore.add(ResourceDisplay.text("&f"+cycle.title()));
                lore.add(ResourceDisplay.text("&7还需完成 "+(cycle.requiredCount()-cycle.completedResourceCount())+" 种资源"));
                for (var recommendation:plugin.cooperation().plan(player,id).recommendations().stream().limit(3).toList())
                    lore.add(ResourceDisplay.format("&b{resource} &7缺 "+num(recommendation.remaining())+"，背包可投入 "+num(recommendation.available()),resourceName(recommendation.resource())));
                lore.add(ResourceDisplay.text(text("gui.guide-open","&a点击查看我能做什么")));
            } else lore.add(ResourceDisplay.text(text("gui.guide-empty","&a当前任务已完成或暂无可投入目标")));
            set(holder.inventory,4,Material.SPYGLASS,ResourceDisplay.text(text("gui.guide-name","&b&l我能做什么")),lore);
            set(holder.inventory,24,Material.WHITE_BANNER,text("gui.lands-name","&d我的 Lands 领地"),List.of(text("gui.lands-open","&7选择协作领地、查看领地贡献")));
            set(holder.inventory,20,Material.GOLD_BLOCK,text("gui.towns-name","&6城镇贡献榜"),List.of(text("gui.towns-open","&7本服所有有贡献的 Lands 城镇排名")));
            var profile=plugin.cooperation().ledger().profile(player.getUniqueId());
            var land=plugin.cooperation().selectedLand(player.getUniqueId());
            set(holder.inventory,25,Material.EXPERIENCE_BOTTLE,text("gui.personal-name","&6我的建设记录"),List.of(
                    "&7总贡献分: "+num(profile.totalScore()), "&7补齐资源: "+profile.resourceFinishes()+" 次", "&7连续参与: "+profile.streak()+" 天",
                    profile.totalScore().signum()>0?"&6已获得首次参与记录":"&7首次有效贡献即可获得参与记录", "&7协作领地: "+(land==null?plugin.cooperation().landStatus(player.getUniqueId()):land.name())));
            set(holder.inventory,26,Material.BELL,text("gui.reminders-name","&e个人协作提醒"),List.of("&7当前: "+(profile.reminders()?"开启":"关闭"),text("gui.reminders-toggle","&e点击切换；关闭不影响手动参与")));
        }
        player.openInventory(holder.inventory);
    }
    private void homeTask(Holder holder,int slot,Material icon,String cadence,String name,long now) {
        String id=plugin.questService().currentTaskId(cadence,now);
        if(plugin.questService().cycle(id)!=null) holder.actions.put(slot,id);
        set(holder.inventory,slot,icon,name,taskLore(cadence,id));
    }
    private String missingTaskMessage(String cadence) {
        var reason=plugin.questService().missingTaskAvailability(cadence);
        return text(reason.configKey(),reason.template()).replace("{cadence}",TaskAvailability.cadenceLabel(cadence));
    }
    private List<String> taskLore(String cadence,String id) {
        QuestCycle cycle=plugin.questService().cycle(id);
        if (cycle==null) return List.of(missingTaskMessage(cadence));
        return List.of(text("gui.task-caption","&f{title}").replace("{title}",cycle.title()),
                text("gui.progress-line","&7进度: &f{done}/{required} ({percent}%)").replace("{done}",""+cycle.completedResourceCount()).replace("{required}",""+cycle.requiredCount()).replace("{percent}",cycle.completionPercent().setScale(0,java.math.RoundingMode.DOWN).toPlainString()),
                text("gui.expiry-line","&7结算: &f{time}").replace("{time}",plugin.questService().formatDeadline(plugin.questService().endsAt(id))),
                text("gui.task-effect","&7{effect}").replace("{effect}",plugin.taskEffect(id)));
    }
    private void openHomeTask(Player player,String id,String cadence) {
        if(id==null) { player.sendMessage(missingTaskMessage(cadence)); openHome(player); }
        else openTaskById(player,id);
    }
    private void openTaskById(Player player,String id) { openTaskById(player,id,0); }
    private void openTaskById(Player player,String id,int page) {
        plugin.questService().refreshNetworkTasks(); QuestCycle cycle=plugin.questService().cycle(id);
        if (cycle==null) { player.sendMessage(text("messages.no-task","&c此周期暂无可用任务。")); return; }
        Holder holder=new Holder(View.TASK,id,page);
        holder.inventory=Bukkit.createInventory(holder,54,text("gui.task-title","&8{title} &7| &f{percent}%").replace("{title}",cycle.title()).replace("{percent}",cycle.completionPercent().setScale(0,java.math.RoundingMode.DOWN).toPlainString()));
        Map<String,BigDecimal> backpack=plugin.cooperation().enabled()?plugin.cooperation().inventory(player):Map.of();
        List<String> path=plugin.cooperation().plan(player,id).path();
        List<dev.bordergate.core.CooperationLedger.Claim> claims=plugin.cooperation().enabled()?plugin.cooperation().claims(id):List.of();
        List<String> resources=List.copyOf(cycle.targets().keySet());
        for (int index=page*RESOURCE_SLOTS.length;index<Math.min(resources.size(),(page+1)*RESOURCE_SLOTS.length);index++) {
            String resource=resources.get(index); int slot=RESOURCE_SLOTS[index%RESOURCE_SLOTS.length];
            BigDecimal gap=cycle.remaining(resource).max(BigDecimal.ZERO);
            List<String> lore=new ArrayList<>();
            lore.add(text("gui.resource-progress","&7已投入: &f{done}/{target}").replace("{done}",num(cycle.contributed().getOrDefault(resource,BigDecimal.ZERO))).replace("{target}",num(cycle.targets().get(resource))));
            lore.add(text("gui.resource-click","&e点击投入配置数量")); lore.add(text("gui.resource-shift-click","&eShift+左键投入背包匹配物品"));
            if (plugin.cooperation().enabled()) {
                BigDecimal promised=claims.stream().filter(c->c.resource().equals(resource)).map(dev.bordergate.core.CooperationLedger.Claim::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
                lore.add("&7实际缺口: "+num(gap)+" | 背包可用: "+num(backpack.getOrDefault(resource,BigDecimal.ZERO).min(gap)));
                lore.add("&7已认领计划: "+num(promised)+"；认领不限制真实投入");
                lore.add(path.contains(resource)?"&a推荐完成此项":"&7此项已完成或不是当前推荐路径");
                lore.add(text("gui.land-claim-click","&b右键个人认领一部分；Shift+右键 Lands 领地认领"));
            }
            holder.actions.put(slot,resource);
            set(holder.inventory,slot,material(resource),ResourceDisplay.format(text("gui.resource-name","&f{resource}"),resourceName(resource)),lore.stream().map(ResourceDisplay::text).toList());
        }
        set(holder.inventory,45,Material.ARROW,text("gui.back-name","&e返回"),List.of());
        set(holder.inventory,49,Material.BOOK,text("gui.task-status","&b完成条件"),List.of(text("gui.task-required","&7需要完成 &f{required}/{total} &7种资源").replace("{required}",""+cycle.requiredCount()).replace("{total}",""+cycle.targets().size()),text("gui.task-effect","&7{effect}").replace("{effect}",plugin.taskEffect(id)),text("gui.expiry-line","&7结算: &f{time}").replace("{time}",plugin.questService().formatDeadline(plugin.questService().endsAt(id)))));
        set(holder.inventory,53,Material.SPYGLASS,text("gui.leaderboard-name","&b贡献榜"),List.of(text("gui.leaderboard-lore","&7查看全服贡献排名")));
        if (plugin.cooperation().enabled()) {
            set(holder.inventory,50,Material.COMPASS,text("gui.guide-name","&b我能做什么"),List.of("&7只推荐完成条件需要的资源"));
            set(holder.inventory,51,Material.WRITABLE_BOOK,text("gui.claims-name","&b认领计划"),List.of("&7查看、取消自己的认领"));
        }
        pagination(holder,resources.size(),RESOURCE_SLOTS.length,46,48);
        player.openInventory(holder.inventory);
    }
    void openGuide(Player player) { openGuide(player,plugin.cooperation().recommendedTask(player)); }
    private void openGuide(Player player,String id) {
        if (!plugin.cooperation().enabled()) return;
        plugin.questService().refreshNetworkTasks();
        if (id==null || !plugin.cooperation().actionable(id)) { player.sendMessage(text("gui.guide-empty","&a当前任务已完成、到期或暂无可投入目标。")); openHome(player); return; }
        Holder holder=new Holder(View.GUIDE,id);
        holder.inventory=Bukkit.createInventory(holder,54,text("gui.guide-title","&8我能做什么"));
        QuestCycle cycle=plugin.questService().cycle(id); int index=0;
        for (var r:plugin.cooperation().plan(player,id).recommendations()) {
            if (index>=RESOURCE_SLOTS.length) break;
            int slot=RESOURCE_SLOTS[index++]; holder.actions.put(slot,r.resource()); holder.amounts.put(slot,r.available());
            set(holder.inventory,slot,material(r.resource()),ResourceDisplay.format("&b{resource}",resourceName(r.resource())),List.of("&7全服还缺: "+num(r.remaining()),"&7你的背包可投入: "+num(r.available()),"&7未认领缺口: "+num(r.unclaimed()),
                    r.finishesResource()?"&a本次投入可补齐这一种资源":"&7小量投入同样计入贡献",
                    text("gui.guide-deposit","&a左键投入上方显示数量；不会自动投入其他资源"),text("gui.land-claim-click","&b右键个人认领；Shift+右键 Lands 领地认领")).stream().map(ResourceDisplay::text).toList());
        }
        set(holder.inventory,45,Material.ARROW,text("gui.back-name","&e返回"),List.of());
        set(holder.inventory,48,Material.BOOK,text("gui.task-status","&b完成条件"),List.of("&7已完成 "+cycle.completedResourceCount()+" / 需要 "+cycle.requiredCount()+" 种", "&7不要求填满全部可选资源", "&7任务: "+cycle.title()));
        set(holder.inventory,49,Material.WHITE_BANNER,text("gui.lands-name","&d我的 Lands 领地"),List.of("&7选择你有权限的协作领地"));
        set(holder.inventory,50,Material.WRITABLE_BOOK,text("gui.claims-name","&b认领计划"),List.of("&7认领是计划，不扣除资源"));
        set(holder.inventory,51,Material.BELL,text("gui.invite-name","&b邀请伙伴协作"),List.of("&7只发送给开启提醒的在线伙伴","&7受低频与去重限制"));
        set(holder.inventory,53,Material.COMPASS,text("gui.task-open","&e查看完整任务"),List.of());
        player.openInventory(holder.inventory);
    }
    private void openClaims(Player player,String id,int page) {
        Holder holder=new Holder(View.CLAIMS,id,page); holder.inventory=Bukkit.createInventory(holder,54,text("gui.claims-title","&8协作认领计划"));
        var claims=plugin.cooperation().claims(id);
        for (int i=page*45;i<Math.min(claims.size(),(page+1)*45);i++) {
            var claim=claims.get(i); int slot=i%45; holder.actions.put(slot,claim.id());
            set(holder.inventory,slot,material(claim.resource()),ResourceDisplay.format("&b{resource} × "+num(claim.amount()),resourceName(claim.resource())),List.of("&7认领者: "+claim.playerId().toString().substring(0,8),"&7领地: "+(claim.landName().isEmpty()?"个人认领":claim.landName()),
                    "&7到期: "+plugin.questService().formatDeadline(claim.expiresAt()),claim.playerId().equals(player.getUniqueId())?"&e点击取消自己的计划":"&7只允许认领者本人取消","&7任何人仍可正常投入，不锁资源").stream().map(ResourceDisplay::text).toList());
        }
        if (claims.isEmpty()) set(holder.inventory,22,Material.PAPER,text("gui.claims-empty","&7暂无有效认领"),List.of(plugin.cooperation().claimsAvailable()?"&7右键任务资源即可认领":"&c共享认领服务暂停"));
        set(holder.inventory,49,Material.ARROW,text("gui.back-name","&e返回任务"),List.of()); pagination(holder,claims.size(),45,46,48); player.openInventory(holder.inventory);
    }
    void openLands(Player player) { openLands(player,0); }
    private void openLands(Player player,int page) {
        if (!plugin.cooperation().enabled()) return;
        Holder holder=new Holder(View.LANDS,null,page); holder.inventory=Bukkit.createInventory(holder,54,text("gui.lands-title","&8我的 Lands 协作领地"));
        var ledger=plugin.cooperation().ledger(); var own=plugin.cooperation().selectedLand(player.getUniqueId());
        var lands=plugin.cooperation().playerLands(player.getUniqueId()).stream().sorted(java.util.Comparator.comparing((LandAffiliation land)->ledger.landScore(land.id())).reversed().thenComparing(LandAffiliation::name).thenComparing(LandAffiliation::id)).toList();
        for (int i=page*45;i<Math.min(lands.size(),(page+1)*45);i++) {
            var land=lands.get(i); int slot=i%45; holder.actions.put(slot,land.id());
            set(holder.inventory,slot,Material.WHITE_BANNER,"&d"+land.name(),List.of("&7Lands 成员数: "+land.members(), "&7领地历史贡献分: "+num(ledger.landScore(land.id())),
                    own!=null&&own.id().equals(land.id())?"&a当前协作领地":"&a点击选作协作领地", "&7按提交时归属记分，换领地不转移历史"));
        }
        if (lands.isEmpty()) set(holder.inventory,22,Material.PAPER,text("gui.lands-empty","&7暂无有权限的 Lands 领地"),List.of("&7"+plugin.cooperation().landStatus(player.getUniqueId()),"&7个人认领和贡献仍可正常使用"));
        set(holder.inventory,45,Material.BOOK,text("gui.lands-manage-name","&bLands 成员关系"),List.of("&7领地与成员通过 /lands 管理","&7此面板只选择协作领地，不修改权限"));
        set(holder.inventory,47,Material.GOLD_BLOCK,text("gui.towns-name","&6城镇贡献榜"),List.of(text("gui.towns-open","&7查看本服所有有贡献的城镇")));
        set(holder.inventory,49,Material.ARROW,text("gui.back-name","&e返回"),List.of()); pagination(holder,lands.size(),45,46,48); player.openInventory(holder.inventory);
    }
    void openTownLeaderboard(Player player) { openTownLeaderboard(player,0); }
    private void openTownLeaderboard(Player player,int page) {
        if (!plugin.cooperation().enabled() || !player.hasPermission("bordergate.use")) return;
        var rows=plugin.cooperation().townLeaderboard();
        int last=rows==null||rows.isEmpty()?0:(rows.size()-1)/45;
        Holder holder=new Holder(View.TOWNS,null,Math.max(0,Math.min(page,last)));
        holder.inventory=Bukkit.createInventory(holder,54,text("gui.towns-title","&8本服城镇贡献榜"));
        if (rows==null) set(holder.inventory,22,Material.BARRIER,text("gui.towns-unavailable","&cLands API 暂不可用"),List.of("&7暂不显示城镇榜，个人功能仍可使用","&e点击下方刷新重试"));
        else {
            var selected=plugin.cooperation().selectedLand(player.getUniqueId());
            for (int i=holder.page*45;i<Math.min(rows.size(),(holder.page+1)*45);i++) {
                var entry=rows.get(i); var land=entry.land();
                List<String> lore=new ArrayList<>(List.of("&7累计贡献分: &f"+num(entry.score()),"&7Lands 成员数: "+land.members(),"&7本服城镇累计榜；不改变任务分红"));
                if(selected!=null&&selected.id().equals(land.id())) lore.add("&a你的当前协作城镇");
                set(holder.inventory,i%45,entry.rank()<=3?Material.GOLD_BLOCK:Material.WHITE_BANNER,
                        text("gui.town-rank-name","&f#{rank} &b{name}").replace("{rank}",""+entry.rank()).replace("{name}",land.name()),lore);
            }
            if(rows.isEmpty()) set(holder.inventory,22,Material.PAPER,text("gui.towns-empty","&7暂无有贡献的城镇"),List.of("&7成功提交会计入当次有效协作领地"));
            pagination(holder,rows.size(),45,46,48);
        }
        set(holder.inventory,47,Material.WHITE_BANNER,text("gui.lands-name","&d我的 Lands 领地"),List.of("&7管理自己的协作领地选择"));
        set(holder.inventory,49,Material.ARROW,text("gui.back-name","&e返回"),List.of());
        set(holder.inventory,50,Material.CLOCK,text("gui.towns-refresh","&e刷新城镇榜"),List.of("&7重新读取排名与 Lands 名称"));
        set(holder.inventory,53,Material.WRITABLE_BOOK,text("gui.history-name","&b守护者榜单"),List.of("&7查看个人贡献榜"));
        player.openInventory(holder.inventory);
    }
    private void openNetworkTasks(Player player) { openNetworkTasks(player,0); }
    private void openNetworkTasks(Player player,int page) {
        var tasks=plugin.questService().refreshNetworkTasks(); Holder holder=new Holder(View.NETWORK,null,page);
        holder.inventory=Bukkit.createInventory(holder,54,text("gui.network-title","&8全服任务"));
        for (int i=page*45;i<Math.min(tasks.size(),(page+1)*45);i++) {
            var task=tasks.get(i); int slot=i%45; holder.actions.put(slot,task.taskId());
            Component progress=Component.empty();
            for(var entry:task.targets().entrySet()) {
                if(!progress.children().isEmpty()) progress=progress.append(Component.text(" | "));
                progress=progress.append(resourceName(entry.getKey())).append(Component.text(" "+num(task.contributed().getOrDefault(entry.getKey(),BigDecimal.ZERO))+"/"+num(entry.getValue())));
            }
            Component displayedProgress=progress;
            Component lore=ResourceDisplay.text(text("gui.network-task-lore","&7{cadence} | {progress}").replace("{cadence}",TaskAvailability.cadenceLabel(task.cadence())))
                    .replaceText(builder->builder.matchLiteral("{progress}").replacement(displayedProgress));
            set(holder.inventory,slot,Material.PAPER,ResourceDisplay.text(text("gui.network-task-name","&f[{server}] &b{title}").replace("{server}",task.serverId()).replace("{title}",task.title())),List.of(lore));
        }
        if(tasks.isEmpty()) set(holder.inventory,22,Material.BARRIER,text("gui.network-empty","&c暂时没有可用的全服任务"),List.of());
        set(holder.inventory,49,Material.ARROW,text("gui.back-name","&e返回"),List.of()); pagination(holder,tasks.size(),45,46,48); player.openInventory(holder.inventory);
    }
    private void openHistory(Player player,String id) { openHistory(player,id,0); }
    private void openHistory(Player player,String id,int page) {
        plugin.questService().refreshNetworkTasks(); String selected=id==null?plugin.questService().currentTaskId("daily"):id;
        Holder holder=new Holder(View.HISTORY,selected,page); holder.inventory=Bukkit.createInventory(holder,54,text("gui.history-title","&8守护者排行榜"));
        QuestCycle cycle=plugin.questService().cycle(selected); var entries=plugin.questService().leaderboard(selected);
        if (cycle!=null) for (int i=page*45;i<Math.min(entries.size(),(page+1)*45);i++) {
            var row=entries.get(i); String name=Bukkit.getOfflinePlayer(row.getKey()).getName(); if(name==null)name=row.getKey().toString().substring(0,8);
            set(holder.inventory,i%45,i<3?Material.GOLD_INGOT:Material.PAPER,text("gui.rank-name","&f#{rank} &b{name}").replace("{rank}",""+(i+1)).replace("{name}",name),List.of(text("gui.rank-score","&7守护分: &f{score}").replace("{score}",row.getValue().setScale(2,java.math.RoundingMode.DOWN).toPlainString()),text("gui.rank-percent","&7分红占比: &f{percent}%").replace("{percent}",cycle.playerPercent(row.getKey()).setScale(2,java.math.RoundingMode.DOWN).toPlainString())));
        }
        if(plugin.cooperation().enabled()) set(holder.inventory,50,Material.GOLD_BLOCK,text("gui.towns-name","&6城镇贡献榜"),List.of(text("gui.towns-open","&7查看本服城镇累计排名")));
        set(holder.inventory,49,Material.ARROW,text("gui.back-name","&e返回"),List.of()); pagination(holder,entries.size(),45,46,48); player.openInventory(holder.inventory);
    }
    private void openAdmin(Player player) {
        Holder holder=new Holder(View.ADMIN,null); holder.inventory=Bukkit.createInventory(holder,27,text("gui.admin-title","&8边界稳定器管理"));
        set(holder.inventory,10,Material.LIME_DYE,text("gui.settle-daily","&a结算每日任务"),List.of());
        set(holder.inventory,12,Material.GOLD_NUGGET,text("gui.settle-weekly","&6结算每周任务"),List.of());
        set(holder.inventory,14,Material.NETHER_STAR,text("gui.settle-monthly","&d结算每月任务"),List.of(text("gui.monthly-effect-lore","&7完成提高总上限，未完成降低总上限")));
        set(holder.inventory,16,Material.DIAMOND,text("gui.reload","&e重载任务配置"),List.of());
        set(holder.inventory,4,Material.PAPER,text("gui.border-size","&b当前边界直径: &f{size}").replace("{size}",""+plugin.trackedSize()),List.of(text("gui.border-status","&7{status}").replace("{status}",plugin.borderStatus()),text("gui.border-bounds","&7下限 {min} / 当前总上限 {max}").replace("{min}",""+plugin.minimumSize()).replace("{max}",""+plugin.effectiveMaximum())));
        set(holder.inventory,21,Material.RED_CONCRETE,text("gui.shrink","&c收缩 128"),List.of());
        set(holder.inventory,23,Material.GREEN_CONCRETE,text("gui.expand","&a扩张 128"),List.of());
        set(holder.inventory,26,Material.ARROW,text("gui.back-name","&e返回"),List.of()); player.openInventory(holder.inventory);
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory()!=event.getView().getTopInventory() || !player.hasPermission("bordergate.use")) return;
        if (holder.view == View.OVERVIEW) {
            int slot = event.getRawSlot();
            if (!event.isShiftClick() && event.isLeftClick() && (slot == 48 || slot == 49
                    || (slot == 45 && holder.page > 0) || (slot == 53 && holder.inventory.getItem(53) != null))) {
                player.getScheduler().run(plugin, task -> {
                    if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder(false) != holder) return;
                    if (slot == 48) player.closeInventory();
                    else openOverview(player, holder.page + (slot == 45 ? -1 : slot == 53 ? 1 : 0));
                }, null);
            }
            return; // No read-only event can ever reach payment, claims, navigation or admin branches.
        }
        synchronized (plugin.questService()) {
            int slot=event.getRawSlot(); String action=holder.actions.get(slot);
            switch(holder.view) {
                case HOME -> { if(slot==10)openHomeTask(player,action,"daily");else if(slot==12)openHomeTask(player,action,"weekly");else if(slot==14)openHomeTask(player,action,"monthly");else if(slot==16)openHistory(player,null);else if(slot==18&&plugin.getConfig().getBoolean("network.enabled",false))openNetworkTasks(player);else if(slot==20)openTownLeaderboard(player);else if(slot==22&&player.hasPermission("bordergate.admin"))openAdmin(player);else if(slot==4&&action!=null)openGuide(player,action);else if(slot==24)openLands(player);else if(slot==26&&plugin.cooperation().enabled()){plugin.cooperation().toggleReminders(player.getUniqueId());openHome(player);} }
                case TASK -> {
                    if(slot==45)openHome(player);else if(slot==53)openHistory(player,holder.taskId);else if(slot==50)openGuide(player,holder.taskId);else if(slot==51)openClaims(player,holder.taskId,0);
                    else if(slot==46&&holder.page>0)openTaskById(player,holder.taskId,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openTaskById(player,holder.taskId,holder.page+1);
                    else if(action!=null){if(event.isRightClick()&&plugin.cooperation().enabled())player.sendMessage(plugin.colorMessage(plugin.cooperation().claim(player,holder.taskId,action,event.isShiftClick())));else contribute(player,holder.taskId,action,event.isShiftClick(),null);openTaskById(player,holder.taskId,holder.page);}
                }
                case GUIDE -> {
                    if(!plugin.cooperation().enabled())return;
                    if(slot==45)openHome(player);else if(slot==49)openLands(player);else if(slot==50)openClaims(player,holder.taskId,0);else if(slot==51)plugin.cooperation().invite(player,holder.taskId);else if(slot==53)openTaskById(player,holder.taskId);
                    else if(action!=null){if(event.isRightClick())player.sendMessage(plugin.colorMessage(plugin.cooperation().claim(player,holder.taskId,action,event.isShiftClick())));else contribute(player,holder.taskId,action,true,holder.amounts.get(slot));openGuide(player,holder.taskId);}
                }
                case CLAIMS -> { if(slot==49)openTaskById(player,holder.taskId);else if(slot==46&&holder.page>0)openClaims(player,holder.taskId,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openClaims(player,holder.taskId,holder.page+1);else if(action!=null){player.sendMessage(plugin.cooperation().cancelClaim(player.getUniqueId(),action)?"已取消你的认领。":"仅能取消自己的有效认领，或共享服务当前不可用。");openClaims(player,holder.taskId,holder.page);} }
                case LANDS -> { if(!plugin.cooperation().enabled())return;if(slot==49)openHome(player);else if(slot==47)openTownLeaderboard(player);else if(slot==46&&holder.page>0)openLands(player,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openLands(player,holder.page+1);else if(action!=null){player.sendMessage(plugin.colorMessage(plugin.cooperation().selectLand(player.getUniqueId(),action)?"&a已选择协作领地，之后的成功提交按当时领地记分。":"&c领地已不存在、你已无权限或选择未保存。"));openLands(player,holder.page);} }
                case NETWORK -> {if(slot==49)openHome(player);else if(slot==46&&holder.page>0)openNetworkTasks(player,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openNetworkTasks(player,holder.page+1);else if(action!=null)openTaskById(player,action);}
                case HISTORY -> {if(slot==49)openHome(player);else if(slot==50)openTownLeaderboard(player);else if(slot==46&&holder.page>0)openHistory(player,holder.taskId,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openHistory(player,holder.taskId,holder.page+1);}
                case TOWNS -> {if(slot==49)openHome(player);else if(slot==47)openLands(player);else if(slot==50)openTownLeaderboard(player,holder.page);else if(slot==53)openHistory(player,null);else if(slot==46&&holder.page>0)openTownLeaderboard(player,holder.page-1);else if(slot==48&&holder.inventory.getItem(48)!=null)openTownLeaderboard(player,holder.page+1);}
                case ADMIN -> handleAdmin(player,slot);
            }
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {if(event.getView().getTopInventory().getHolder(false) instanceof Holder)event.setCancelled(true);}
    private void contribute(Player player,String id,String resource,boolean all,BigDecimal displayed) {
        QuestCycle cycle=plugin.questService().cycle(id);if(cycle==null)return;
        BigDecimal amount=BigDecimal.valueOf(Math.max(1,plugin.getConfig().getLong("gui.deposit-amount",1))).min(cycle.remaining(resource));
        if(all&&resource.startsWith("item:"))amount=plugin.cooperation().inventory(player).getOrDefault(resource,BigDecimal.ZERO).min(cycle.remaining(resource));
        if(displayed!=null)amount=amount.min(displayed);
        if(amount.signum()<=0){player.sendMessage(text("gui.no-deposit","&e没有可投入数量，任务已变化或背包不足。"));return;}
        var result=plugin.questService().contribute(player,id,resource,amount);
        player.sendMessage(plugin.colorMessage(switch(result){case SUCCESS->"&a投入成功，任务与贡献记录已保存。";case INSUFFICIENT->"&c资源不足或经济服务拒绝了交易。";case UNAVAILABLE->"&c资源或网络服务当前不可用。";case INVALID->"&c任务已完成、到期或数量超过剩余目标。";}));
    }
    private void handleAdmin(Player player,int slot) {
        if(!player.hasPermission("bordergate.admin")){openHome(player);return;}
        if(slot==10)settleCurrent("daily");else if(slot==12)settleCurrent("weekly");else if(slot==14)settleCurrent("monthly");else if(slot==16){plugin.reloadSettings();player.sendMessage(text("messages.reloaded","&aBorderGate 已重载"));}else if(slot==21)plugin.adjustBorder(-128);else if(slot==23)plugin.adjustBorder(128);else if(slot==26)openHome(player);else openAdmin(player);
    }
    private void settleCurrent(String cadence) {String id=plugin.questService().currentTaskId(cadence);QuestCycle cycle=plugin.questService().cycle(id);if(cycle!=null)plugin.settleQuest(id,cycle.isComplete());}
    private void pagination(Holder holder,int total,int perPage,int previous,int next) {if(holder.page>0)set(holder.inventory,previous,Material.ARROW,text("gui.previous-page","&e上一页"),List.of());if((holder.page+1)*perPage<total)set(holder.inventory,next,Material.ARROW,text("gui.next-page","&e下一页"),List.of());}
    private Material material(String resource) {Material material=resource.startsWith("item:")?Material.matchMaterial(resource.substring(5)):null;return material!=null&&material.isItem()?material:resource.equals("vault")?Material.GOLD_INGOT:Material.AMETHYST_SHARD;}
    private String num(BigDecimal value) {return CooperationService.number(value);}
    private Component resourceName(String resource) {
        return ResourceDisplay.name(resource,r->plugin.getConfig().getString("resources.names."+r),name->{
            Material item=Material.matchMaterial(name);
            return item!=null&&item.isItem()&&!item.isAir()?item.translationKey():null;
        });
    }
    private void set(Inventory inventory,int slot,Material material,Component name,List<Component> lore) {
        ItemStack stack=new ItemStack(material); ItemMeta meta=stack.getItemMeta();
        meta.displayName(name); meta.lore(lore); stack.setItemMeta(meta); inventory.setItem(slot,stack);
    }
    private void set(Inventory inventory,int slot,Material material,String name,List<String> lore) {ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(colorText(name));meta.setLore(lore.stream().map(this::colorText).toList());stack.setItemMeta(meta);inventory.setItem(slot,stack);}
    private String text(String path,String fallback) {return ChatColor.translateAlternateColorCodes('&',plugin.getConfig().getString(path,fallback));}
    private String colorText(String value) {return ChatColor.translateAlternateColorCodes('&',value==null?"":value);}
}
