# 2.1.7 公共仓库准备与验证

本轮修改构建、验证工具和仓库文档，未重构模组生命周期、定位、容量、安全出生或配置行为；网络协议仍为 6。

## 仓库与构建

- Git 忽略本地缓存、运行目录、配置、日志、备份、参考项目、IDE 产物及可选模组测试副本。必要的 Gradle Wrapper jar 和测试结构 NBT 保留。
- Wrapper 不依赖相邻 Java 安装；使用 JAVA_HOME／PATH，默认项目内缓存并尊重显式 Gradle user home。Gradle 8.8 下载 SHA256 已固定。
- 生产 linkage 审计通过 ForgeGradle 6 的缓存 API 获取其生成的 SRG 中间产物，跟随实际 Gradle user home；不再假定项目 `.gradle-home` 路径。
- 生产 Mixin smoke 的 classpath、模块路径、版本和启动参数由 ForgeGradle runtime 依赖与 userdev 元数据提供。Python 只编排隔离启动和字节码检查，不查找已安装的 Minecraft jar、不读取 HMCL 清单、不 remap。
- Python 命令跨平台选择，并支持 `pythonExecutable` 属性／`PYTHON` 环境变量。测试使用 Gradle toolchain 的 Java 17。
- README、CONTRIBUTING 和 AGENTS 使用通用开发说明。历史审计移入 `docs/history`，链接已检查。冗余手工构建工具、refmap 和 manifest 保留为本地归档，不进入公共仓库。
- AGPL、旧 MIT、Gradle Apache-2.0 和原 NOTICE 保留；MixinExtras 继续携带原 MIT 声明。参考项目副本不分发，来源说明保留。
- GitHub Actions 配置 Windows／Ubuntu 的 build、integration 编译和生产 smoke，仅有内容读取权限，不发布产物、不启动游戏、不接受 EULA。

## 实际验证

1. 当前开发目录执行 `build compileIntegrationJava productionMixinSmoke --offline`：通过，1m31s，30 个任务，23 执行、7 up-to-date。
2. 仅从 Git 暂存内容导出干净源码副本，使用另一个起初为空的 Gradle user home，联网执行相同任务：通过，8m31s，30 个任务全部执行。该副本没有游戏实例清单、libraries、旧构建脚本、手工 refmap、现有 build 或第三方模组；Wrapper 和 Forge/Minecraft 依赖均重新下载／生成。这同时验证了显式缓存位置覆盖。
3. 两次构建均通过 capacity、SavedData、配置、语言、Origins fail-soft、Perimeter、开发／生产 linkage、jar/refmap/许可证检查，以及实际生产 SRG Mixin 转换、等待／START 条件、holding／普通构造分支和非阻塞 chunk invoker 检查。
4. Unix Wrapper 在 Git Bash 下通过语法与 `--version` 检查；这是 Windows JVM，不代表原生 Linux 构建已运行。
5. Git 提交清单、Wrapper 可执行位 100755、忽略规则、Markdown 本地链接、diff 空白和常见密钥模式扫描通过；没有远端配置或提交。

本地验证日志位于忽略的 `build/validation-2.1.7-final.log`、`build/validation-2.1.7-clean-network.log`、`build/unix-wrapper-validation.log`。可按 CONTRIBUTING 中的命令自行复核。

## 验证限制

GitHub Actions 尚未在远端运行；原生 Linux／macOS 尚未实际构建。本轮没有启动客户端、创建服务端世界、接受 EULA 或运行两客户端联机。此前未完成的独立服务端完整世界与多人实机回归仍需进行，详见历史世界验证记录。此轮干净构建证明源码和构建工具可脱离既有游戏实例运行，不宣称不同平台产物逐字节一致。
