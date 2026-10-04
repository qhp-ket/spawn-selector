# 源码审计（迁移前 2.0.13）

项目是单模块 Forge，已有 src/main/java 和 resources，无 common/forge 多模块。包名 dev.tide.spawnselector 保留。

调用链：SpawnSelector 注册事件/网络/reload → MinecraftServerMixin 延后初始 spawn → SpawnSelectorDimensions 创建 holding 平台 → PlayerListMixin/ServerPlayerMixin 在构造、NBT 后放置、重生时路由 → SelectionEligibility + PlayerSelections → SelectionService 独立 UUID Session → OriginsCompat → SpawnEntries/Locators → SafeSpawnFinder → teleport → ClientSelection 的 chunk/mesh 确认。

| 功能 | 实际实现 |
|---|---|
| holding | void flat 维度；单区块内 5×5 barrier 平台，(8.5,64,8.5) |
| 首次加入 | SavedData chosen/pending + PLAY_TIME<=200；构造与登录包发出前路由 |
| 冻结/免伤/交互 | Session 事件拦截、位置校正；EntityMixin 仅禁止 holding 玩家互推/碰撞 |
| Origins | 无硬引用；反射检查 hasAllOrigins 和 callback readiness，不抢 GUI |
| Origins 异常 | API shape/linkage BROKEN 会放行；普通 invocation failure 按 UUID 每 2 秒重试，可能无限等待 |
| Session | 每 UUID 独立 WAITING/PICKING/LOCATING/SEARCHING/VANILLA/REVEAL；logout 关闭 finder |
| 玩家存储 | spawnselector_players schema 1：entry/dimension/position/pending；正常仅 setDirty |
| locate | ID/tag、generateStructures、placement 验证；同步 nearest 单实例，读取 STRUCTURE_STARTS/REFERENCES |
| 安全落点 | 稀疏外围 512 上限；实际 standing dimensions 与移动后的 bounding box；noCollision、fluid、border、支撑、unsafe/leaves、高差、目标 footprint/其他 3D bounds |
| 预加载 | 每 finder UUID ticket + FULL future；32 touched chunks / 600 ticks 上限；找到后再次验证 |
| 传送 | 设置个人重生点，旧实现没有面向结构中心；客户端收到区块且 mesh 编译稳定两 tick 后 ACK；400 tick 服务端超时放行 |
| deferred spawn | setInitialSpawn HEAD 取消原版地形搜索；climate sampler 只取 anchor；prepareLevels 跳 START ticket 并跳 441 等待，其余原版准备保留 |
| 放弃/恢复 | materialize 121 chunk spiral、玩家 spawnRadius 搜索；恢复 START radius 11；WorldSpawnState 持久化保证不重复；无数据视为旧世界 |
| config/GUI/network | datapack + 外部差异 JSON；Mods 配置编辑器/结构目录/选择页面；SimpleChannel 协议 4，服务端重新校验 ID |
| 旧构建 | build.py 找实例 jar，MapNames/FART remap，手工 javac/refmap；test.py 几何/JSON/JAR/linkage；runtime_test.py 生产 SRG Mixin 转换检查（不运行世界） |

迁移顺序：先标准 ForgeGradle/Wrapper/Java17/AP refmap/reobf，验证旧代码；保留旧工具；再将 target resolution、多实例 discovery、候选持久化/claim、spawn safety 分责。保留现有生命周期与 deferred 代码，不以参考项目替换主体。

审计的现有限制：大型 locate 同步；固定 open sky；Origins 普通异常无限重试；客户端 ACK 超时会解冻；目前无多人容量。旧日志和 LEVEL2 明确没有完整游戏回归，不能把历史构建通过当作游戏验收通过。

本文件以上内容记录迁移前事实；迁移后的修复、职责划分及实际回归结论见 [VALIDATION-2.1.0.md](VALIDATION-2.1.0.md)，使用方法见 [README.md](../../README.md)。
