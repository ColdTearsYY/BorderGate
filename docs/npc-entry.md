# NPC 入口与权限

普通玩家默认从 NPC 打开 BorderGate 菜单。菜单里的投入、认领、领地选择、贡献榜和翻页继续可用；手输命令默认仅 OP 可用。本轮没有新增 `npc-only` 开关，而是把命令入口与菜单参与权限分开。

## 权限

| 权限 | 默认值 | 作用 |
| --- | --- | --- |
| `bordergate.use` | 所有玩家 | 允许 NPC 代开和菜单内参与；撤销后菜单点击也会拒绝。 |
| `bordergate.command` | OP | 允许玩家手输 `/bg`、`/bgate`、`/bordergate` 及全部子命令；还必须有 `bordergate.use`。 |
| `bordergate.admin` | OP | 允许管理菜单和结算／边界操作，不能由命令权限替代。 |

别名和 `bordergate:bg` 等命名空间形式走同一入口检查。无命令权限时不提供 BorderGate 子命令补全。`open` 代开只允许服务器控制台；玩家即使是 OP，也不能替别人打开。命令方块、代理执行者和 RCON 不接受为代开控制台。

普通玩家不要授予 `bordergate.command` 或 `bordergate.*`。如果旧权限组已经给过通配权限，应在权限插件中检查玩家的最终有效权限。

如果使用 LuckPerms，可在控制台设置普通组，例如：

```text
lp group default permission set bordergate.use true
lp group default permission set bordergate.command false
lp group default permission set bordergate.admin false
```

已有管理员组需要单独保留／授予这三项权限，且确认不被继承的否定权限覆盖。没有安装 LuckPerms 时，用现有权限插件做相同设置；BorderGate 不会自动修改权限组。

## 入服弹窗与升级

新安装默认关闭入服自动打开：

```yaml
gui:
  open-on-join: false
```

升级不会覆盖旧配置。旧配置为 `true` 时，入服弹窗也必须同时有 `bordergate.use` 和 `bordergate.command`；延迟回调会重新检查设置、权限和在线状态。希望所有玩家只通过 NPC 入场时，把旧值改成 `false`，再重载 BorderGate 配置或完整重启。

升级保留 `config.yml`、`data.yml`、`quests.yml`、`cooperation.yml` 和数据库，只替换 JAR。不要删数据重新生成，不要用强制结算测试入口。

## 先在控制台验证代开

让测试玩家在线，在服务器控制台执行，不加斜杠：

```text
bordergate open TestPlayer
```

把 `TestPlayer` 换成完整在线名字，不支持缩写或离线玩家。只要求目标有 `bordergate.use`；不要求目标有命令或管理员权限。菜单通过目标玩家调度器打开，执行前再检查在线状态和使用权限。已离线、权限撤销或调度器退出时给控制台失败提示，不报告打开成功。

这条命令只打开菜单，不扣资源、不结算任务、不改边界、不授予临时权限或 OP。控制台仍是可信管理入口：不要给普通玩家执行任意控制台命令的能力。

## FancyNpcs 配置

按当前 [官方 `/npc` 命令文档](https://fancyinnovations.com/docs/minecraft-plugins/fancynpcs/commands/npc) 和 [Action System 文档](https://fancyinnovations.com/docs/minecraft-plugins/fancynpcs/tutorials/action-system)，给已有 NPC 添加右键控制台动作：

```text
/npc action bordergate_keeper RIGHT_CLICK add console_command bordergate open {player}
```

把 `bordergate_keeper` 换成你的 NPC 名称。若需要新建 NPC，可由管理员先在所需位置执行 `/npc create bordergate_keeper`。这些是管理员配置命令，不需要授给普通玩家。

`{player}` 是 FancyNpcs 自带的点击者名字占位符，这个占位符不依赖 PlaceholderAPI。动作中的 `bordergate open ...` 不加斜杠。必须使用 `console_command`，不要使用 `player_command` 或 `player_command_as_op`。

添加前先查看现有动作，避免重复打开：

```text
/npc action bordergate_keeper RIGHT_CLICK list
/npc action bordergate_keeper ANY_CLICK list
```

若同一个 NPC 已有旧的 `player_command bg`、临时 OP 或重复的代开动作，按实际列表编号用 FancyNpcs 的 `remove`／`set` 修改指定项；不要直接清空其他动作。只添加一次，再查看列表确认。用户实际 FancyNpcs 版本尚未核实，若服务器补全与上述语法不同，先执行 `/fancynpcs version` 并按该版本文档配置，不盲目改已有动作。

## 上线前检查

使用一个非 OP、只有 `bordergate.use` 的测试账号：

- 手输 `/bg`、`/bg guide`、`/bg towns` 和别名应被拒绝，且不给子命令补全。
- 入服不自动弹菜单；右键 NPC 只打开自己的菜单。
- 菜单内能查看任务、少量真实投入、认领／取消和翻页；认领不扣物品。
- 没有管理按钮。不要为了测试授予 OP 或提前结算任务。
- 临时撤销 `bordergate.use` 后，NPC 代开和已打开菜单的投入都应拒绝；测试后恢复原权限。

这是入口限制，不是 NPC 距离限制。打开后可继续操作菜单，不检查离 NPC 的距离或会话时长；关闭后需再次点击 NPC，除非玩家另有命令权限。

## 验证边界

本地隔离验收使用真实 Bukkit Inventory／菜单监听器和合成玩家、玩家调度器夹具，不等于真人客户端、实际 FancyNpcs 点击或 Folia 玩家线程端到端联调。官方动作语法已经核对，正式服未部署。

用户日志中的 ChunkyBorder 1.2.23 未能加载本插件所需官方 API，仍会保护性暂停边界调整；NPC 入口更新不修复这个兼容问题。不要据菜单能打开就判断边界可写。先按 [首次使用与状态排查](getting-started.md) 核对边界状态。
