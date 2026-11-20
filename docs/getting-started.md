# BorderGate 首次使用与状态排查

加载插件后，任务生成和定时检查会自动启动，不需要 `/bg start`；当前版本没有这个命令。已有边界不会因为加载而立即扩张。任务能打开只说明任务系统可用，边界接口是否就绪要另看状态。

## 现在先做什么

由 OP 或同时拥有 `bordergate.use`、`bordergate.command` 的管理员在游戏内输入：

```text
/bg status
```

查看最上方管理世界、边界状态和每日／每周／每月任务。控制台使用 `bg status`，不加斜杠。

| 输出 | 含义和下一步 |
| --- | --- |
| `ChunkyBorder \| 当前直径 ...` | 边界接口已能读取，当前使用 ChunkyBorder；原版 `/worldborder get` 不会跟着变化。 |
| `vanilla \| 当前直径 ...` | 当前使用原版 worldborder，不调整 ChunkyBorder。 |
| `已暂停: 管理世界未加载` | 检查 `plugins/BorderGate/config.yml` 的 `world` 是否为实际 Bukkit 世界名称。 |
| `已暂停` 且提示 ChunkyBorder 未设置边界 | 先确认要为哪个世界创建边界，再按下面的初始化说明操作。 |
| `已暂停` 且提示 API 不可用 | 查看启动日志中的 `Border changes suspended` 和 ChunkyBorder 报错，初始化命令不能修复 API 故障。 |
| 任务 `进行中`，有目标和截止时间 | 该任务可参与；尚未完成或到期时，边界不变正常。 |
| 任务 `已完成，等待结算` | 查看 `schedule.settle-on-completion`；若关闭，等截止结算；若开启，查看结算日志和边界暂停原因。 |

`/bg cooperation` 可补充查看 Lands、认领和协作服务状态。任务正常、Lands 正常、边界正常是不同检查项，不能只看一个菜单判断全部服务正常。

## 开始参与任务

1. 普通玩家点击已配置的 NPC 打开菜单，再进入每日、每周或每月任务；NPC 配置见 [NPC 入口说明](npc-entry.md)。有命令权限的玩家也可输入 `/bg`。
2. 看任务完成条件。三选四只需填满其中三种，不要求所有可选资源都完成。
3. 背包带上目标物品，普通左键按配置数量投入；完整任务面板 Shift＋左键投入背包内匹配物品，仍受剩余缺口限制。
4. 也可点主页“我能做什么”，按推荐缺口投入。这里的左键最多投入打开时显示的数量。
5. 看到“投入成功”后，在任务面板查看进度；管理员也可用 `/bg status` 核对。投入一点不等于完成整个任务，所以通常不会立刻改变边界。

要让贡献计入城镇，先在“我的 Lands 领地”选择有权限的协作领地；授权命令用户可用 `/bg lands`。没有领地也能做个人贡献。右键认领只是分工计划，不扣物品，不推进任务；推进任务需要左键实际投入。

新安装默认 `gui.open-on-join: false`，不会进服自动弹菜单。普通玩家默认保留菜单参与权限，但没有手动命令权限；旧配置仍为 `true` 时，入服弹窗也要求同时拥有两项权限。NPC 入口只限制打开方式，不限制离开 NPC 后继续操作已经打开的菜单。

## 边界何时变化

- 日／周任务达成完成条件，且 `schedule.settle-on-completion: true` 时，自动发起成功结算；默认该开关开启。
- 关闭即时结算时，等任务截止再结算；未达标按失败策略收缩。期限检查约每分钟一次。
- 月任务默认将本服当前总上限调整 ±5%。成功提高总上限通常不会立即扩大实际边界；失败后只有实际边界超出新上限时才收回。
- 日／周实际增减受上下限和自动曲线影响。已到总上限时成功扩张可能为0，已到下限时失败收缩可能为0；具体预估在任务面板和 `/bg status` 中显示。
- 参加其他服务器的任务时，只由所属服务器结算其边界，不调整你当前所在服的边界。

原版 worldborder 默认使用约30秒原生过渡，周期任务可单独配置时长。ChunkyBorder 官方 API 是即时调整，不显示原版过渡。每次任务投入不会单独扩一小段边界。

## 为什么两套边界没有一起变

插件一次只使用一个权威边界来源。安装并启用 ChunkyBorder，且 `integrations.chunky-border: true` 时选择 ChunkyBorder；否则使用原版 worldborder。启用 ChunkyBorder 但 API 故障时会暂停，不偷偷改原版边界。

边界来源在启动时选择；改 `integrations.chunky-border` 后需要完整重启服务器。仅装 Chunky 不等于装了 ChunkyBorder；Chunky 的预生成选区也不是实体边界。原版和 ChunkyBorder 不做双向镜像同步。

`border.initial-size` 不是每次启动的强制尺寸。原版只在首次初始化且世界仍是默认未设置的大边界时采用初始值；已有边界和已保存状态保留。`minimum-size` 和 `maximum-size` 是允许范围，不是命令“现在改到这个尺寸”。

## 仅在尚未设置边界时初始化

确认 `/bg status` 的管理世界正确，确认这个世界确实没有需要保留的边界，再由 OP 执行。例如你明确想创建直径3000格的边界：

```text
/bg initialize 3000
/bg status
```

**这会真实创建或修改边界，不是只启动插件。已有边界不要把它当成必做步骤。** 数值是直径，不是半径，并受本服有效上下限限制。创建前确认 `border.initial-center-x/z`；原版初始化会采用配置中心，已有 ChunkyBorder 初始化保留其中心和几何比例。API不可用、世界未加载时需要先修对应问题。

正常启动无需 `/bg setsize`、`/bg reset` 或 `/bg settle`。尤其不要在正式服用 `/bg settle daily` 验证是否工作：它会提前结算，未达标可能收缩边界，并且本期任务会结束。若确需测试边界写入，用独立测试服，并先记录原尺寸。

## 仍不清楚是否工作时

提供 `/bg status` 完整输出，以及启动日志中含 `BorderGate`、`Border source=`、`Border changes suspended` 或 `ChunkyBorder` 的相关行。说明希望管理哪个世界、用哪套边界。配置只需提供 `world`、`border`、`schedule`、`automation` 和 `integrations` 等相关项，不要发数据库密码。

仅凭“两个边界看起来没变”无法区分正常等待结算、看错世界、缺少边界和API故障。保留 `config.yml`、`data.yml`、`quests.yml`、`cooperation.yml`，不要删数据来试着启动。
