# Spawn Selector 2.1.0 实际验证记录

日期：2026-10-03。目标 Minecraft 1.20.1、Forge 47.4.23、Java 17。所有测试世界、日志、缓存、参考快照和 Origins 测试副本均位于本项目。没有向正式 mods 安装 jar，没有修改正式 config/saves，也没有推送或发布。

## 审计与迁移

原始项目已经是单模块 Forge，具有 src/main/java/resources；不是 multiloader。原版 2.0.13 的 UUID Session、holding、反射 Origins compat、外围安全检测、客户端确认和 deferred vanilla spawn 为迁移主体。原始事实和调用链见 [AUDIT.md](AUDIT.md)。

先完成 ForgeGradle 基线构建，再实施功能修改。源码备份为 backups/pre-forgegradle-source.zip，旧行为的 ForgeGradle 基线产物为 backups/forgegradle-baseline.jar。旧 build.py/test.py/runtime_test.py 保留；生产转换 smoke 暂使用旧脚本的只读运行依赖收集，但标准 build 不使用旧构建、手工 remap 或手工 refmap。

[build.gradle](../../build.gradle) 使用 ForgeGradle 6.0.54、MixinGradle 0.7-SNAPSHOT、官方 1.20.1 mappings、Java toolchain/release 17、Wrapper 8.8。Mixin AP 正常生成 refmap，processResources 带入开发运行资源，reobfJar 生成生产 SRG 包。两种 jar 均验证资源、默认 JSON、Java 17 字节码和生成的 refmap，不含测试 mod、参考项目或 Minecraft/Forge 依赖。

最终命令 `gradlew.bat build productionMixinSmoke runServer --offline` 成功，日志为 [build/final-acceptance.log](../../build/final-acceptance.log)。其中 runServer 返回 EULA 门槛，不能解释为服务端世界已验收。

| 检查 | 最终结果 |
|---|---|
| build | BUILD SUCCESSFUL，30 actionable tasks，34s（含 smoke 和 server 启动） |
| perimeterTest | 10000 随机 footprint、负坐标、宽大结构、preferred side、唯一性与预算通过 |
| capacityTest | 1/2/-1、最后槽位串行竞争、幂等、临时释放、永久 claim、恢复通过 |
| savedDataTest | 真实 NBT schema 0/1 升级、schema 2 往返、临时 reservation 清理、未来版本保护、恢复确认通过 |
| originsCompatTest | 异常分类、有限重试、UUID 隔离、重连清理通过；故意异常日志属于 fixture |
| 开发/生产 linkage | 两包各 4259 个 Minecraft/Forge/selector 引用解析通过 |
| verifyArtifacts | 两包 Java17、资源、AP SRG refmap、无依赖捆绑通过 |
| productionMixinSmoke | 初始 spawn/START/441/玩家路由等生产 Mixin hook 有真实调用点；非阻塞 chunk invoker 转换通过 |

最终生产 jar SHA256：`E01449CE32D0369EA0935738EB91D619E6A8151FA96543852540F9D5C06A996E`。

最终开发 jar SHA256：`879FF31B76E43D1089668CC1E2046F06814576DF2F84781344E90B2942B7A8E7`。

## 客户端实际运行

[tests/world/ClientRegression.java](../../tests/world/ClientRegression.java) 是仅开发运行加载的测试 mod。它创建/打开隔离世界，调用实际界面的按钮回调，经真实网络消息、ServerPlayer、地形生成和客户端 renderer ACK 完成流程。它不是屏幕截图视觉验收，也不是只调用生产函数的模拟测试。成功后 Minecraft 正常保存并退出。build 产物不包含此 mod。

| 场景 | 实际结果与固定日志 |
|---|---|
| 普通 runClient | Forge 客户端到标题画面；之后用户关闭；完整流程由下列回归覆盖 |
| 新世界结构出生 | 初次 holding 时 overworld 已加载区块计数为 0；发现多个村庄；实例页选择、外围安全传送、个人重生点、真实 ACK、永久 claim 和 COMPLETE 通过；[client-final-body-check-world.log](../../build/client-final-body-check-world.log) |
| 放弃 | holding → vanilla materialize → START ticket 恢复 → 玩家原版出生位置 → ACK/COMPLETE，通过；[client-vanilla-final-world.log](../../build/client-vanilla-final-world.log) |
| 结构世界重进 | 不重新展示选择、不重新 locate、不 materialize、不恢复 START，完成 claim 保留；[client-restart-verified-world.log](../../build/client-restart-verified-world.log) |
| vanilla 世界重进 | 已 materialize 的世界恢复 START，不重复初始化原版出生点；[client-vanilla-restart-world.log](../../build/client-vanilla-restart-world.log) |
| stale holding NBT 恢复 | fixture 备份并回退测试玩家 NBT，但保留 COMPLETE SavedData；重进恢复原实例出生点 (677,119,279)，重新 ACK 后 COMPLETE，claim 保留，materialized=false/START=false；[client-holding-recovery-world.log](../../build/client-holding-recovery-world.log) |

最后的正常结构测试已包含传送后真实体型再次校验。NBT 恢复测试运行在最终代码上；恢复之后最终 build/smoke 再次通过。其他测试在对应功能完成时运行，后续仅修改恢复/体型确认边界，没有重复所有测试。

初始日志中的 `Preparing start region` 是原版日志行，不能单独据此判断 441 preparation 恢复：Mixin 仍执行 prepareLevels 的其他流程。测试同时检查实际 loaded chunk 计数、WorldSpawnState 和 START ticket；结构场景始终 materialized=false/START=false。确认过“放弃”及其重进的相反状态。

## Origins 四种状态

读取正式 mods 的 Origins 1.10.0.9 和 Caelus 3.2.0 jar，在 test-mods 生成隔离副本及其 Forge JarJar mod 依赖，通过可选 ForgeGradle deobf 加载。没有改变正式 jar，生产源码和默认 build 没有 Origins 硬依赖。

| 状态 | 验证 |
|---|---|
| 未安装 | 普通结构/放弃场景 ABSENT，正常通过 |
| 安装且未选 | 实际 ChooseOriginScreen 存在；holding/WAITING，Origins API 未完成时 Spawn Selector 不展示；选择默认种族后再进入 Selector；[client-origins-world.log](../../build/client-origins-world.log) |
| 已选 | 重进真实 Origins 测试世界，API ready=true、完成记录保留、不重复 holding/Selector；[client-origins-restart-world.log](../../build/client-origins-restart-world.log) |
| API broken | 测试侧注入 NoSuchMethodError，实际 compat 记录 BROKEN warning 并降级，之后正常结构出生；[client-origins-broken-world.log](../../build/client-origins-broken-world.log) |

普通 invocation failure 的三次重试和单玩家绕过由 originsCompatTest 覆盖；正常 API 返回未完成不会按固定 tick 超时放行。

## 八项实际世界 GameTest

在渲染客户端的 integrated server 中由真实 GameTestRunner 执行，8/8 通过：[build/client-world-fixtures-final-world.log](../../build/client-world-fixtures-final-world.log)。源码 [tests/world/SpawnSelectorGameTests.java](../../tests/world/SpawnSelectorGameTests.java)。

1. safeflathazardsandactualbody：实际地表、流体/岩浆块、悬空支撑、玩家宽 bounding box 碰撞。
2. footprintandopenskypolicy：目标 XZ footprint 排除、遮顶时 open sky true/false 政策；等待真实光照更新。
3. actualpoolcapacityandrestart：在 server thread 用 Alice/Bob/Carol 的不同 UUID 操作真实池，验证同/不同实例、1/2/-1、最后容量槽位、完成保留、临时释放、NBT 恢复。
4. multivillagediscovery：3 个不同实际村庄实例，23/512 STARTS probes，发现阶段 FULL requests=0。
5. holdinganddeferredstate：holding 平台和未 materialize/无 START 状态。
6. concentricstrongholddiscovery：2 个不同真实 stronghold，2/128 STARTS probes，FULL requests=0。
7. targetfilteringandbiomeviability：overworld 中不可能生成的 vanilla Nether fossil 被排除，tag 和 wildcard exclusions 正确。
8. denseperimeterfindsmissedgap：构造稀疏采样遗漏的安全间隙，真实 finder 进入有限 dense fallback 并找到落点。

三名 UUID 的池测试及 10000 次最终槽位顺序测试验证服务器线程原子性；它们不等于两台客户端实际同时联网验收。

## 最终职责与行为

生产包名 dev.tide.spawnselector 保留。

| 文件（src/main/java/dev/tide/spawnselector 下） | 职责/关键变化 |
|---|---|
| SelectionService.java / compat/OriginsCompat.java | 保留每 UUID 生命周期和保护；Origins 异常有限、隔离；接入实例选择、claim 和 ACK |
| SpawnSelectorManager.java | 每服务器共享发现任务，server tick 推进并提交，配置改变取消 |
| StructureTargetResolver.java | ID/tag、exclusions、placement 和 biome source viability；自定义/不确定 API 保守 fallback |
| MultiStructureLocator.java | RandomSpread potential starts / Concentric ring positions，实际 start 核验；未知 placement 单实例 fallback |
| StructureInstanceLocator.java | 仅在选中后读取 start/bounds，不扫描 pieces |
| StructureInstanceId.java / ClaimLedger.java / StructureCandidatePool.java | 稳定实例身份，容量及永久/临时 claim，恢复与目标 membership |
| PlayerSelections.java | schema 2 同一 SavedData 保存候选与玩家，旧完成数据 LEGACY_COMPLETE，不强迫重选；setDirty 而非同步 save |
| SpawnSafetyValidator.java / SafeSpawnFinder.java / Perimeter.java | 保留原安全政策；稀疏→有希望区段加密；open sky 配置；真实体型和传送后再校验 |
| ChunkPreparation.java / mixin/ServerChunkCacheAccessor.java | 服务器线程请求不含 managedBlock 的内部 chunk future；避免公共 getChunkFuture 隐式阻塞 |
| TeleportLifecycle.java / ClientSelection.java / Packets.java | 到达期间维持目标 ticket；维度/位置/本次 token 的客户端 chunk/mesh ACK；超时告警但不自动解除保护；协议 5 |
| SpawnSelectorDimensions.java | 保留 holding；构造阶段返回玩家也避开 unused vanilla 区域；恢复 stale holding NBT |
| mixin/MinecraftServerMixin.java / DeferredVanillaSpawn.java / WorldSpawnState.java | 保留 initial spawn、START、441 延迟；放弃才 materialize；持久化后重进不重复 |
| SpawnEntry.java / SpawnEntryOptionsScreen.java / SpawnSelectionScreen.java | 新配置和具体实例卡片；原 GUI/config/datapack 保留 |

实例身份 dimension + registry ID + start chunk；candidate_count 1–32（默认 3），capacity_per_instance=-1 或 1–10000（默认 -1）。同一实例跨配置别名采用最严格有限容量。临时 claim 在取消/失败/传送前掉线释放，重启清理；实际安全到达后永久保留，ACK 决定解冻。管理员 reset 不自动回收历史永久 claim。已到达掉线仍占容量。

发现阶段状态 DISCOVERED，仅记录 start；选中才 PREPARING/FULL，成功 READY。cached spawn 仍须按新玩家体型校验。限制 8192 potential planning、512 actual probes、1200 ticks；安全搜索全程 32 FULL 请求区块、600 ticks，dense 最多 8 区段/256 点。没有对搜索半径全部 chunk FULL 扫描。

BetterVillageSpawnPoint 1.20.1 LICENSE 已核实 CC0-1.0，只借鉴过滤/placement/biome viability 思路并独立实现，不替换主体、不借用村庄内部落点算法。出处见 [NOTICE.md](../../NOTICE.md)。

## 尚未完成的实际验收 / TODO

- Dedicated server：runServer 加载到 EULA=false 门槛（最终 refmap 正常读取/重映射）；没有创建服务端世界，没有两客户端联机。此前已请求仅测试目录接受 EULA 的明确确认，尚未收到。runGameTestServer 同样需要 EULA，不能报告为已完成世界回归。
- 多人实机：Alice/Bob 同时首次加入，不同目标/相同目标不同实例/同实例最后槽位，容量 1/2/-1，准备期间掉线、服务器重启继续、各自 Origins 状态互不干扰。当前已有真实池线程/NBT 测试，仍需验证网络与生命周期组合。
- 整合包：大型/超宽模组结构、树林/水边/山坡/悬崖组合地形，modded dimensions/custom placement，Origins/其他体型模组在维度切换时改变尺寸。宽 >8/高 >16 目前明确拒绝；不将未知 biome source/structure 判定为绝对不存在。未知 placement 只保证原版单实例 fallback。
- GUI 视觉与交互：不同 GUI scale、实例分页、配置页、其他 GUI/Overlay 共存。自动回归验证实际按钮及流程，没有截图视觉验收。
- 生产 jar 实机：生产 linkage/refmap/转换检查已通过，完整世界回归运行在开发 mappings；正式整合包加载生产 jar 的世界/联网回归仍待进行。两端必须同步升级至网络协议 5。
- 旧脚本清理：标准构建完全替代旧编译/reobf/refmap；旧工具仍保留。待 dedicated/生产 jar 实机验收完成，再考虑移除冗余部分，并将可选 smoke 的只读依赖收集彻底独立。

此前测试 harness 调整造成的失败日志仍保留于 build 作为诊断历史；最终通过证据是本记录所链接的日志，不应把全部历史 FAIL 行混为最终验收结果。
