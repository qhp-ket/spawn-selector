# Spawn Selector 2.1.5

## Redirect 替换

生产源码中的 6 处 `@Redirect` 全部替换；holding／Origins／每玩家 Session／candidate pool／SavedData／传送确认流程保留。

| 文件 | 替代实现 |
| --- | --- |
| MinecraftServerMixin.skipStart | `WrapWithCondition`：存在 holding 时只阻止 START ticket，其他调用照常 |
| MinecraftServerMixin.skipWait | `Definition` + `Expression` + `ModifyExpressionValue`：仅修改是否继续等待的布尔比较结果，不伪造 441 或要求 0；正常分支返回原比较结果 |
| PlayerListMixin.placement | `ModifyExpressionValue`：将实际返回的维度传给既有 route，继续更新原版局部变量，保证登录包、游戏模式和实体插入维度一致 |
| PlayerListMixin.respawnPos | `ModifyExpressionValue`：pending 玩家返回 null；其他玩家保留实际床位置返回值 |
| PlayerListMixin.respawnFallback | `WrapOperation`：pending 玩家回 holding；其他玩家 materialize 后执行传入的 operation |
| ServerPlayerMixin.constructionPosition | 静态 `WrapOperation`：父构造前 holding 返回固定位置，避免共享出生点／高度图读取；其他维度调用传入的 operation |

`prepareLevels` 后半段没有取消：原版 forced chunks、ForgeChunkManager.reinstatePersistentChunks、progress.stop、updateMobSpawningFlags 仍在。初始 spawn 搜索的既有取消注入保留；放弃后 materialize 和已物化存档重启的 ticket 恢复逻辑未改。

表达式按 `getTickingGenerated()` 的比较定位，右侧用 wildcard，不写死目标为 441；MixinExtras 的 Definition 由 AP 生成 `m_8427_()` 生产映射。生产转换后布尔 hook 的 false 分支直接跳出等待循环。

## 构建与分发

使用 MixinExtras 0.5.5；common 作为编译／AP 依赖，forge 包通过 ForgeGradle JarJar 嵌套，保留原 MIT 许可。两级嵌套元数据均检查，正式包及开发包不依赖整合包其他模组提供此库。

生产包为 `jarJar`／`reobfJarJar` 产物：`build/libs/spawnselector-2.1.5-forge-1.20.1.jar`。开发包仍为 `spawnselector-2.1.5-dev.jar`。`-thin.jar` 仅作中间产物；linkage、资源、refmap 和安装检查指向实际生产包。

实际调用检查还发现原入口类的客户端配置注册 lambda 会让 dedicated-server 加载 Screen。`SpawnSelector.ClientSetup` 将这段代码隔离到客户端嵌套类，common 入口及 LOG 不再包含客户端 Screen 方法签名，客户端注册仍由 DistExecutor 执行。

## 验证

执行 `gradlew.bat build compileIntegrationJava productionMixinSmoke --offline`；日志 `build/validation-2.1.5-final.log`。

最终命令成功（1m 25s，29 个任务），生产 smoke 返回 0，日志出现 `SPAWNSELECTOR_HOOK_REGRESSION PASS`。开发／生产包各通过 5038 个运行时引用的 linkage 检查。

- 外围几何、capacity、SavedData、Origins fail-soft、Component／名称／真实配置文件检查。
- 开发／生产 linkage、Mixin AP refmap、JarJar 依赖与 MIT 许可、默认资源与 AGPL 元数据。
- 检查生产／开发 mixin class 均没有 Redirect 注解。
- 隔离的 Forge DEDICATED_SERVER 环境加载真实生产包，验证全部注入点实际转换与调用位置，及非阻塞 chunk invoker。
- `HeadlessLaunch` 用未初始化的 server／level 测试夹具调用实际转换后的 hook，不创建或读取世界：count=0／1／289／441／512、target=441／289 时 deferred 都跳过，正常状态保留比较结果；START 条件只在 holding 存在时阻止，其他 ticket 保留；holding 构造不执行 supplied operation，正常构造执行一次且参数正确。
- 游戏测试代码编译；未运行图形客户端或完整 dedicated world。

测试启动器补齐 SharedConstants 版本检测和 Bootstrap，未同意 EULA、未启动 Minecraft 窗口、未修改实例 config 或 saves。核心注入点实际执行已验证，但与全部整合包模组共同运行，以及登录／死亡／放弃／保存重启的完整世界回归，仍建议下次正常启动时检查。

成功后只替换 mods 中的活动 Spawn Selector 正式 jar，不新增备份；唯一活动包、SHA256 和正式模组配置只读校验记录于 `build/local-install/installation-2.1.5.json`。
