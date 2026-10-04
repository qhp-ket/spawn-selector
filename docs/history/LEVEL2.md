# SpawnSelector v2 / Level 2 交付记录

日期：2026-09-18。当前版本：2.0.13；网络协议：4（客户端、服务器需要同版）。

## 备份与改动

工作区没有 Git 仓库，已与 `backups/v1-final` 逐文件对比，清单见 `build/v2-change-list.txt`。
v1-final 保存了源代码、构建文件、资源、测试及原 JAR。
原 JAR SHA256：`8b72e10e82ea6f8ef802c0d7d8839a1636e6c299803d35b04be7fae2b467763a`。

新增 DeferredVanillaSpawn、WorldSpawnState、SelectionEligibility、SpawnSelectorDimensions；
修改 SelectionService、客户端状态/恢复页面、网络协议及注册入口。
新增三个 Mixin、Mixin 配置/refmap、holding JSON、轻量运行测试。
构建器负责从当前映射生成 production SRG refmap；已有结构定位、安全落点算法和配置编辑器未重构。

## 三个加载入口

| 入口 | 精确处理 |
| --- | --- |
| A | MinecraftServer.setInitialSpawn 静态 HEAD 注入；非 debug 世界记录 climate anchor 和占位出生坐标后取消原版地形安全搜索。debug 原分支本身不搜索地形。 |
| B | MinecraftServer.prepareLevels 重定向 ServerChunkCache.addRegionTicket，holding 存在时跳过 START；重定向 getTickingGenerated 返回 441，跳过等待。方法其余强制区块恢复与收尾保留。 |
| C | PlayerList.getPlayerForLogin 的 ServerPlayer 构造参数 index 1 改成 holding；ServerPlayer 构造函数 getSharedSpawnPos 调用改成固定 holding 坐标；fudgeSpawnLocation HEAD 在 holding 中直接定位并取消原安全搜索；placeNewPlayer 中 getLevel 返回值在 NBT 读取后、登录包与实体加入前改成最终路由维度。 |

PlayerList.respawn 另重定向 getRespawnPosition 和 overworld fallback：待选择者不查询旧床、回 holding；普通无床重生先实体化默认出生点。
已经完成/旧玩家的临时对象仍在 holding 构造，正式加入按保存的维度进行，不向客户端发送 holding 登录状态。

## 候机维度

`data/spawnselector/dimension/holding.json`：

```json
{"type":"minecraft:the_end","generator":{"type":"minecraft:flat","settings":{"biome":"minecraft:the_void","features":false,"lakes":false,"layers":[{"block":"minecraft:air","height":1}],"structure_overrides":[]}}}
```

固定位置 (8.5,64,8.5)，5×5 屏障平台位于单个区块内，无常驻 START ticket。等待 Origins 时也冻结和保护。
缺少 holding 时记录错误并回退旧流程；这种降级不提供“默认地区零加载”保证。

## 生命周期

资格统一为：尚未完成选择，并且 PLAY_TIME <= 200 或 pending=true。
资格符合者首次正式加入 holding，记录 pending；断线重连仍候机。
玩家独立选择，不修改其他玩家选择。结构选择仍从共享出生锚点定位，成功后设置个人重生点。

永久放弃先显示加载页，再执行延后的原版 11×11 世界共享出生点搜索；随后按 `ServerPlayer.fudgeSpawnLocation()` 的顺序，在 `spawnRadius` 与世界边界内查找 `getOverworldRespawnPos()` 候选，并以玩家实际碰撞箱选择真正落点。成功后记录 vanilla，不设置个人强制重生点。shared spawn 仅是搜索中心，不再被错误地当作玩家必须精确站立的位置。
世界 SavedData 持久保存 deferred/materialized/anchor/placeholder 与奖励箱标记。
首次成功放置奖励箱后不重复放置；放置失败保留待处理标记。外部已修改共享出生坐标时保留外部坐标。
实体化使用 ServerLevelData.setSpawn 并广播出生坐标，不调用会在错误时机添加 START ticket 的 ServerLevel.setDefaultSpawnPos。实体化完成后显式恢复半径 11 的 START ticket；以后每次启动，已经实体化的世界也会恢复该 ticket。新世界仍只在选择原版出生前保持无 START ticket。
旧世界缺少该 SavedData 时默认视为已经实体化，不重新初始化世界出生点。
异常发生在 holding 时提供“前往原版出生地点”恢复页面，避免解除冻结后滞留虚空。

## 已完成验证

- Java 17 目标编译及 SRG 重映射成功，产物 `build/spawnselector-2.0.0-forge-1.20.1.jar`。
- 10,000 组落点外围几何测试通过；2,772 处运行时引用解析通过；资源/defaults 一致性检查通过。
- 隔离 Forge 47.4.23 启动环境实际转换 MinecraftServer、ServerPlayer、PlayerList；九个注入点均在导出字节码中存在调用。
- A/B/C 均经本机 1.20.1 字节码审查和 Mixin 转换验证。导出结果为 `build/*-v2-transformed.txt`；日志 `build/v2-runtime/console.log`。
- 运行测试只加载类，不创建世界，不连接用户存档；没有代替用户接受 EULA。

## 尚未实测与限制

2.0.1 让已完成选择的玩家在 getPlayerForLogin 构造阶段直接保留真实维度；未完成者才使用 holding。非 holding 的异常恢复会保留 session、pending 和保护状态，并显示无需结构查询的恢复页面。2.0.2 修复首次放弃与 reset 后放弃共用的 vanilla-entry 落点错误。
2.0.3 将异常遗留 holding 的恢复分支也统一到 `preparePlayerSpawn()`，所有 holding 到 vanilla 的路径均先实体化 shared spawn、再寻找玩家实际落点。外围地面搜索从“首批 5/总共 M”开始显示，后续按“已检查 N/总共 M”更新。
2.0.4 在成功传送后保留选择界面和服务端保护，客户端确认玩家所在区块已经收到、中心区块网格已编译并连续稳定两个客户端 tick 后发送 `@world_ready`；服务端收到回执才关闭界面，另设 400 server-tick 兜底。外围搜索改为当前候选序号，从 `5/总共 M` 开始，候选 6 开始时立即更新为 `6/总共 M`。
2.0.5 仅在两个 `ServerPlayer` 同时位于 holding 时禁止互相碰撞和 `push(Entity)`；不使用 scoreboard team 或 `noPhysics`，其他玩家与实体行为不变。
2.0.6 以唯一的 `HOLDING_VEC(8.5,64.0,8.5)` 进行放置和校正。平台在服务器启动时建立，之后每 20 tick 最多完整检查一次，只在被破坏时重建。REVEAL 状态显示服务器下发的“正在进入世界…”，并将传送失败与传送后记录/遮罩收尾失败分开处理，避免成功传送被误报为失败。
2.0.7 备份了旧 1.1 README，并更新为当前 v2 使用说明；删除公开的 `/spawnselector skip`，UI 的永久放弃行为和内部 `@skip` 网络动作保留。
2.0.8 修复跨维度加载期间恢复旧选择界面时未应用 state=6 的问题；忙碌状态不再显示空结构列表提示。
2.0.9 增加过期选择界面的客户端清理：服务端已发送关闭状态后，即使跨维度加载界面恢复旧 SpawnSelector，也会自动关闭。
2.0.10 将 Origins 兼容状态区分为 ABSENT、WAITING、READY、BROKEN；只有 WAITING 阻塞选择，永久 API 不兼容时按未安装 Origins 处理并记录一次错误。
2.0.11 细化 Origins 反射调用异常：InvocationTargetException 的兼容性 cause、ClassCastException 和 IllegalArgumentException 进入 BROKEN，其余调用异常继续限时重试。
2.0.12 将 Origins 临时反射失败的 2 秒冷却改为按玩家 UUID 保存，单个玩家的故障不会阻塞其他玩家。
2.0.13 将 state=6 的“正在加载世界…”提示移到屏幕中央并放大，隐藏上方选择标题及旧卡片内容。

按本轮节省额度要求，没有进行完整进服、Origins 联动、多人/死亡/重启或实际区块计数测试。
这里的运行验证是 Mixin 转换验证，不是端到端游戏验收。测试环境使用本机 patched client classes 和 SERVER dist，并非独立 dedicated-server 安装测试。
因此不能声称已实测证明整合包全过程“零主世界区块”。其他模组主动加载主世界、恢复已有强制区块、已有在线玩家活动均不被本模组禁止。
延后的原版出生搜索仍同步执行，首次选择原版可能短暂阻塞服务器；本轮没有改变原版搜索算法。
新世界在自选前不再触发原版 CreateSpawnPosition 搜索事件路径；依赖该路径的出生改写模组需单独兼容验证。
不要把本次构建当作完整游戏回归已通过的正式稳定版；建议先在新测试世界验收后用于长期存档。
