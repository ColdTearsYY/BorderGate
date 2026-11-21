# BorderGate

BorderGate 将世界边界维护做成每日、每周、每月协作任务。默认自动选任务并根据边界位置计算扩张／收缩，不需要手填目标表或固定奖惩。玩家通过 GUI 查看推荐缺口、认领分工、投入资源和查看贡献榜；跨服共享任务、提交、排名与认领，各服边界与总上限独立。

## 安装与首次配置

1. 使用 Java 25 和 Maven 执行 `mvn clean verify`。
2. 将 `target/bordergate-0.1.1.jar`（或 GitHub Release 的同版本 JAR）安装到 Folia/Lophine 服务器的 `plugins` 目录并启动。
3. 确认管理世界和 `border.minimum-size`、`border.maximum-size`。自动运营默认开启，不必填写任务目标、固定扩张值或经济命令。
4. 普通玩家默认点击配置好的 NPC 打开菜单；OP 或同时有 `bordergate.use`、`bordergate.command` 的玩家可输入 `/bg`。默认入服不自动弹菜单。菜单文字支持 `&` 颜色码，缺项使用内置默认值。

第一次使用先读 [首次使用与状态排查](docs/getting-started.md) 和 [NPC 入口配置](docs/npc-entry.md)。插件加载后自动运行，没有“开始”命令；管理员执行 `/bg status`（控制台 `bg status`）确认边界来源／是否暂停，再通过菜单投入任务。已有边界不会因加载立即改变；正常增减发生在任务结算，ChunkyBorder 与原版边界不同时同步。

Vault、PlayerPoints、PlayerCurrency、Chunky 和 ChunkyBorder 均为可选插件。自动任务只使用物品，不依赖经济插件；货币奖励命令默认留空，需要发币时再显式配置。手动资源支持 `item:MATERIAL`、`vault`、`playerpoints`、`playercurrency:<type>`。PlayerCurrency 的目标、扣款和奖励精度必须是整数。

## 0.1.1 投入反馈优化

成功提交只发送一次两行反馈，物品名称与 GUI 共用客户端翻译和服务器自定义名称；本次贡献分最多显示两位小数，不改变记账精度。第二行显示周期与资源进度，仅在适用时提示首次参与、补齐和实际城镇归属；任务名称与连续参与天数放在悬停详情。任务面板、推荐面板、命令及关闭合作引导时共用成功反馈，支付失败不会发送成功提示。

升级保留全部配置、状态文件与数据库，停服替换旧 JAR 后启动，不同时保留两份 BorderGate 插件。本次发布只提供源码和 Release 下载，未部署到 1、2 区服务器。

## 默认自动运营

### 日／周自平衡曲线

位置 `x = clamp((当前直径 - 下限) / (当前总上限 - 下限), 0, 1)`，平滑系数 `s = x²(3 - 2x)`。本期预算为上下限之差的固定比例：每日默认 1%，每周默认 4%；成功扩张 `预算 × (1 - s)`，失败收缩 `预算 × s`，最终不超上下限。上下限相同则不变化。

因此越靠近下限，完成时扩得越多、未完成罚得越少；靠近上限则相反。以 1000–12000 为例：下限每日成功 +110、失败不缩；中点 6500 每日成功 +55、失败 -55；上限每日不再扩张，每周失败 -440。`automation.daily-range-percent`／`weekly-range-percent` 只是可选调速参数。效果以结算时边界为准；原版过渡未结束时按目标累加，GUI 和 status 展示当前预估。

### 150 套任务轮换

内置 30 个生存主题 × 5 种四资源组合，共 150 套任务，每套要求完成三种或四种。昂贵成品与普通物资按成本分档设置数量；周数量是日基础的 4 倍，月为 12 倍。按服务器、周期和任务 ID 确定性抽取，各周期尽量避开最近 7 套；资源不合法或在 `automation.excluded-resources` 中时排除整套任务。只有兼容任务全被最近记录排除时才允许重复。合作引导开启后，新任务数量还会参考本服已结算任务与参与记录渐进调整；系统不检测世界实际可获取程度，可按服务器玩法排除不适用物品。

任务生成后，模板、目标、完成条件、期限和月度比例保存至 `quests.yml`；查询、重载、重启不重抽。已有旧任务沿用保存的期限和日／周结算策略，新周期才进入自动模式。需要手动覆盖时设 `tasks.<周期>.source: manual`，或关闭 `automation.enabled`；此时日／周的 `targets`、`required-count`、`select-random`、`border-expand`、`border-shrink` 生效。

### 月任务调整本服总上限

完成：当前总上限 × 1.05；未完成：当前总上限 × 0.95。`tasks.monthly.maximum-change-percent` 默认 5，可选覆盖。调整不低于下限，也不超过后端允许值；完成通常只提高上限，不直接扩当前直径。失败后若当前直径超过新上限，使用原后端收回。

有效总上限为 `border.maximum-size × data.yml 中的 border.maximum-multiplier`，本服独立保存，月任务应用收据防止重试重复叠比例。改基础 `maximum-size` 会同比改变有效上限，操作前应备份状态；不要删除 `data.yml` 来日常升级。外部 API、状态文件和 MySQL 不构成严格原子事务，异常恢复需要核对日志。

### 周期与手动奖励

`schedule.timezone`／`rollover-hour` 控制本地日历换期：每日该时刻、每周一该时刻、每月一日该时刻，默认 UTC 零点。北京时间凌晨 4 点设 `Asia/Shanghai`／`4`。该时刻前属于上一日／周／月，已有期限不自动迁移。

`rewards.per-player` 是每人奖励基数，`fixed` 每人直接领取，`pool` 形成“基数 × 有效人数”总池后按贡献分配；`amount-scale` 控制精度，整数货币设 0。默认 `commands: []`，不会擅自执行发币命令。

## 默认合作引导

主页新增“我能做什么”、我的 Lands 领地、个人建设记录和提醒设置。推荐只列完成条件仍需要的资源，左键投入不超过打开时显示数量。右键个人认领，Shift＋右键代表选中的 Lands 领地认领。计划最多 1 小时，不扣资源、不锁实际投入，任何人仍能贡献。

协作单位只用 Lands：只读查询领主／信任权限，不创建小队、不维护成员名单、不限制人数。成功提交按当次有效领地记历史分，改名不丢分、换领地不搬分；API故障暂停领地认领，个人功能继续。首次／补齐／连续参与反馈不额外发币，不改变 fixed／pool。提醒可关闭；至少 3 个同周期已结算自动任务后才渐进调整未来自动目标，当前和手动目标不变。

操作及保存兼容见 [合作引导使用说明](docs/cooperation-guide.md)。命令后备：`/bg guide`、`/bg lands [select <id>]`、`/bg towns`、`/bg reminders`、`/bg cooperation`；领地及成员管理使用 `/lands`。升级保留全部状态文件；旧小队仅归档，不映射到 Lands。

## 城镇贡献榜

主页“城镇贡献榜”或 `/bg towns` 查看本服全部有有效贡献的 Lands 城镇，不限于自己加入的领地。按历史累计贡献分降序，同分按名称／稳定ID排序；每页45条、名次连续，支持翻页与刷新。个人榜、“我的 Lands 领地”及城镇榜之间可切换。城镇榜只读，不切换协作领地、不扣资源、不改分红。

改名保留分数并显示当前名称；已删除城镇不进入当前榜单，但历史累计分保留。API故障显示暂不可用，与空榜区分。沿用 `cooperation.yml` 的已有领地累计分，无需新增数据库表或手工设置城镇。国家榜、逐笔城镇明细和跨服城镇总榜未添加。

## 贡献分与分红

个人贡献分按每次投入累加：

`贡献分 = 投入量 / 该资源目标量 × 100 × 资源权重 × 周期倍率`

资源权重位于 `resources.weights`，周期倍率位于对应任务的 `score-multiplier`。有效参与者是本任务贡献分大于 0 的玩家。个人奖励百分比为个人贡献分除以本任务全体贡献分。固定模式每人领取基数；分红模式总池按贡献比例分配，并用最大余数法补齐舍入差额，确保总分配额等于总奖池。控制台命令奖励可在 `tasks.<周期>.rewards.commands` 配置，支持 `{player}`、`{uuid}`、`{amount}`、`{percent}`、`{participants}`、`{pool}` 和 `{task}` 占位符。命令由控制台执行。旧版 `rewards.pool` 仍作为 `per-player` 缺省回退，但建议迁移到新字段。

## 全服 MySQL / Redis

每台服务器都需要启用 `network.enabled`，配置相同的 MySQL 数据库，并设置唯一的 `network.server-id`。各服应使用一致的任务配置、资源权重和经济 provider 规则。

MySQL 是共享任务、额度预留、提交记录、聚合进度和排行榜的权威存储。任务 ID 包含所属服务器标识，GUI 的“全服任务”面板可以查看并提交其他服务器的任务；只有任务所属服务器会结算该任务并改变自己的边界。数据库用额度预留避免并发服务器超额扣款，扣款失败会取消预留。未完成的预留超过五分钟后会在后续预留时自动释放。

Redis 目前仅发布任务、进度和结算通知，没有订阅接收实现；权威状态不存于 Redis。GUI 和周期检查通过 MySQL 读取快照。`network.redis.enabled` 可选启用发布。已有数据库仅补列，不删表；自动任务模板、曲线标记与月比例随任务定义保存，重复同步不覆盖既有目标与期限。

网络状态变更与外部经济 provider 无法组成同一个原子事务。若服务器在扣款后、MySQL 提交前发生断连，插件会记录错误和预留编号；应检查玩家扣款与对应数据库记录后人工核对，不要直接重复扣款。未提供可用 MySQL 时插件回退为本地任务，跨服功能不可用。

## 菜单与权限

配置中已提供的 `gui.*` 面板标题、按钮名、说明和状态文字支持 `&` 颜色码；缺项会回退到默认文案。动态资源与成员记录说明仍由插件生成。`gui.open-on-join` 默认 `false`；显式开启时，入服自动打开仍要求使用和命令两权限。`gui.deposit-amount` 设置完整任务面板普通左键投入数量，Shift＋左键投入背包匹配物品；右键用于认领。

主页周期按钮显示具体任务标题；推荐提示明确写每日／每周／每月，避免把月任务与每日按钮混淆。新周期尚未生成的任务会在打开菜单时补齐；已发布任务不重抽。停用、无可用模板、手动目标为空分别提示原因。物品显示跟随客户端语言，可通过 `resources.names` 指定服务器名称，支付和认领仍使用原资源 ID。

`bordergate.use` 默认所有玩家可用，只控制菜单参与；`bordergate.command` 默认 OP，玩家手输主命令／全部别名／子命令必须同时有这两权限，无权限不提供补全。`bordergate.admin` 独立控制管理菜单和操作，默认 OP，命令权限不会授予管理权限。管理员 GUI 提供日／周／月结算、配置重载和边界调整。命令后备为 `/bg settle [daily|weekly|monthly|legacy]`（默认 daily）、`/bg donate <resource> [amount] [daily|weekly|monthly]`、`/bg initialize`、`/bg setsize`、`/bg reload`。`reset` 和 `legacy` 仅用于旧维护入口，不重置自动任务。

仅服务器控制台可执行 `bordergate open <在线玩家名>`，为只有 `bordergate.use` 的目标代开；按完整在线名称查找，通过目标玩家调度器执行并重新检查权限／在线状态。玩家即使是 OP、命令方块、代理执行者和 RCON 都不能使用代开。FancyNpcs 配置 `console_command bordergate open {player}`，不要临时 OP；完整操作及官方来源见 [NPC 入口配置](docs/npc-entry.md)。只限制入口，不限制离 NPC 后继续使用已打开菜单。

`/bg status` 显示有效上下限、日／周／月任务 ID、状态、达成种类、逐资源进度、真实期限和剩余时间，以及日／周当前预估效果或月上限比例。它不再读取旧维护目标；查询不重抽或改写期限。已结算、到期、完成待结算与缺任务均明确标注。

## 边界来源与原版动态过渡

默认 `integrations.chunky-border: true`。检测到已启用的 ChunkyBorder 后，BorderGate 读取该世界现有边界，启动和重载不会用旧 `data.yml` 或初始配置覆盖它。任务结算、GUI 增减、`/bg setsize` 和 `/bg initialize` 均通过官方 API 修改 ChunkyBorder。中心、形状、X/Z 轴比例和 wrap 保留；已有直径即使超出本插件的上下限，启动时也不改动，下一次明确调整才应用上下限。插件中的直径定义为 `2 × max(radiusX, radiusZ)`，非正方形边界等比缩放两轴。未设置边界时暂停调整，可先用 ChunkyBorder 设置，或显式执行 `/bg initialize <直径>` 创建。接口故障/插件停用时暂停，不静默改动另一套边界。

未安装 ChunkyBorder，或设置 `integrations.chunky-border: false` 后重启，使用原版 `WorldBorder`。原版扩张和收缩直接调用 Bukkit 的原生定时 `setSize`，不是逐步命令或插件模拟动画。初次启用时只有默认未设置的原版边界会初始化；已有边界和原版的伤害、警告设置保留。

```yaml
border:
  transition-seconds: 30 # GUI、setsize 和旧维护入口；0 为立即变化
tasks:
  daily:
    border-transition-seconds: 30
  weekly:
    border-transition-seconds: 120
```

任务过渡字段缺省时继承 `border.transition-seconds`，该字段缺省为 30 秒。`/bg setsize <直径> [秒数]` 可单次覆盖，例如 `/bg setsize 4000 60`，`/bg setsize 4000 0` 立即完成。`initialize` 是明确初始化，立即生效。原版过渡期间的新增减按尚未完成的目标尺寸累加，再从当前实际尺寸开始新的原生过渡，避免连续每日/每周结算丢失变化量。目标尺寸、世界和结束时间存入 `data.yml`，正常重启后按剩余时间恢复；停机超过结束时间则完成到目标尺寸。GUI 和 `/bg status` 显示当前尺寸、目标和剩余时间，后台约每秒刷新尺寸。

ChunkyBorder 的官方 API 没有时长参数，上述过渡配置只作用于原版边界。ChunkyBorder 模式直接修改其实际边界，不制造虚假的原版动画或改变另一套边界。仅使用 Chunky 时，在原版过渡完成后同步预生成选区，不在动画开始时同步一个中间尺寸。边界来源选项变更需要重启；原版边界和 ChunkyBorder 不做双向镜像同步。

参考：[Bukkit WorldBorder API](https://jd.papermc.io/paper/26.3/org/bukkit/WorldBorder.html)、[ChunkyBorder 官方 API](https://github.com/pop4959/ChunkyBorder/blob/master/common/src/main/java/org/popcraft/chunkyborder/api/ChunkyBorderAPI.java)。跨数据库结算、外部边界 API 和状态文件无法构成一个原子事务，故障恢复仍应核对日志，不保证崩溃窗口内严格 exactly-once。

## 验证与限制

0.1.1 投入反馈版通过 Java25 `mvn verify` 的 **213 项测试**（0 失败／错误／跳过），以及原版＋真实 Chunky／ChunkyBorder 的 **402 项隔离检查、8 次采样、4 次正常退出**。覆盖消息去重、鹦鹉螺壳翻译、自定义名称、数字精度、关闭合作引导、失败路径及原 NPC／Lands／城镇榜／重载／重启回归。使用合成玩家／调度器和官方 LandsAPI 夹具；未进行真人、实际 FancyNpcs／Lands9.3.0 或生产 MySQL 端到端联调。仅发布 GitHub 源码与 Release，1、2 区未部署。下方记录均对应历史包。

NPC 入口版通过 Java 25 `mvn verify` 的 **196 项测试**，0 失败／错误／跳过；新包通过原版＋真实 Chunky／ChunkyBorder 的 **376 项隔离检查、8 次采样**，4 次正常启动／重启并 stop 退出。覆盖主命令／别名／命名空间拒绝、无权限补全、控制台精确目标代开、离线／撤权／调度器退出、旧入服弹窗配置、仅使用权限下菜单投料／认领／翻页、管理员权限独立，以及 Bug1、Lands 和城镇榜回归。证据见 `Temp/npc-entry/build-r2.log`、`Temp/npc-entry/result-r2.json` 和 `Temp/npc-entry/verification.md`。首轮探针计数断言失败记录保留，不计入最终通过。

本轮只调整入口、权限与入服弹窗，不改 SQL、任务、付款、奖励或边界算法；未重跑双服 MySQL 和完整自动运营隔离场景。以下验收数字分别对应历史包，不冒充当前 NPC 包的新检查。

Bug1 修复版通过 Java 25 `mvn verify` 的 **184 项测试**，0 失败／错误／跳过；新包通过原版＋真实 Chunky／ChunkyBorder 的 **236 项隔离检查、8 次采样**，4 次正常启动／重启并 stop 退出。包含旧“尚未配置”配置、缺失日任务补齐、月任务推荐／标题对应、炼药锅／铁粒／粗铁翻译组件、原 ID 的真实投料与认领、原城镇榜及重载／重启回归。证据见 `Temp/gui-fix/build-r1.log`、`Temp/gui-fix/result-r1.json` 和 `Temp/gui-fix/verification.md`。

此前城镇榜版本通过 Java 25 `mvn clean verify` 的 **175 项测试**，0 失败／错误／跳过，日志 `Temp/out/towns-build-r1.log`。新增8项排名策略及4项官方 Lands API 公共领地查询测试；这些记录仅对应此前城镇榜包。

此前城镇榜 JAR 通过原版＋真实 Chunky／ChunkyBorder 的 **181 项隔离检查、8 次采样**，结果 `Temp/town-integration/result-r1.json`。包含公开城镇榜、48城镇翻页及连续名次、个人／城镇榜切换、只读点击、权限、改名／删除、空榜与API故障／恢复，以及实际物品提交、原Lands认领和保存故障回归、重载／重启。4次启动／重启均正常stop，两个安装包与该版交付包hash一致，运行包不含API夹具／探针或国家榜。该版详细记录见 `Temp/out/towns-verification.md` 和 `Temp/out/towns-artifact.json`。

Bug1 修复只调整任务就绪与 GUI 呈现，不改变 SQL、共享认领、付款、奖励、城镇记账或边界算法；未重跑双服 MySQL 和完整自动运营隔离场景。此前 Lands 版163／202、合作版152／200和城镇版175／181验收仅作历史保留，不冒充当前 JAR 的新检查。

Lands权限和城镇数据使用官方API7.12.7接口夹具，**不等于真实Lands插件联调**；用户启动日志显示 Lands 9.3.0，尚未用该实际插件验收。合成Player＋真实BukkitInventory／菜单监听器不等于真人客户端或Folia玩家线程端到端验证；FancyNpcs 动作语法已按官方文档核对，未进行实际 NPC 点击联调。真实经济插件、实际断网和大规模并发仍未实测；Redis仍只发布通知。正式服未部署、未修改。国家榜、逐笔城镇明细与跨服城镇总榜不在本轮范围。
