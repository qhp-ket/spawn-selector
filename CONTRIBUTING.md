# 参与开发

安装 JDK 17 和 Python 3.8 或更新版本，让 `JAVA_HOME` 指向 JDK，并确保 Python 可执行文件在 PATH。无需已有 Minecraft 实例，也无需系统安装 Gradle。首次构建需要访问 Gradle、Forge、Mojang、Maven Central 和 Sponge 的依赖仓库。

Windows：`gradlew.bat build compileIntegrationJava productionMixinSmoke`；Linux／macOS：`./gradlew build compileIntegrationJava productionMixinSmoke`。Wrapper 默认把缓存写入项目 `.gradle-home`，也接受 `GRADLE_USER_HOME` 或 `--gradle-user-home`；Python 可通过 `-PpythonExecutable=/path/to/python3` 或 `PYTHON` 指定。Windows 默认命令是 `python`，其他平台是 `python3`。

提交源码、资源、构建文件、测试和文档。不要提交缓存、运行配置、存档、日志、第三方模组 jar、本地参考项目或备份。`gradle/wrapper/gradle-wrapper.jar` 和 `src/test/resources` 中的测试结构属于必要仓库文件。

生产包是 `build/libs/spawnselector-<version>-forge-1.20.1.jar`；`-dev.jar` 使用开发映射，`-thin.jar` 是中间产物。构建不会安装到实际游戏实例。Mixin AP 生成 refmap，ForgeGradle 负责 reobf，不能提交手工生成的 refmap 来替代。

`build` 覆盖容量、SavedData、Origins 错误隔离、外围几何、配置、语言、linkage 和产物检查。`productionMixinSmoke` 在隔离目录加载生产包与 Forge SRG 类，执行转换后的实际保护分支，不创建世界或启动游戏窗口。涉及世界生成、网络或 GUI 的修改还需按 README 进行针对性实机测试，并记录没有覆盖的场景。

保持已有玩家生命周期和存档兼容，避免无关包名迁移和大规模重写。Origins 等可选兼容必须隔离且 fail-soft；Minecraft 世界状态与 claim 提交在服务器线程完成。请在 PR 中说明触发问题、最终行为、验证结果和限制。

贡献按本项目 AGPL-3.0-only 提供。第三方代码须先核实来源与许可证并保留说明。历史审计位于 `docs/history`；当前行为以源码和 README 为准。
