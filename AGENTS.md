# 开发约定

- 默认中文回复。
- Java 17、Minecraft 1.20.1、Forge 47.4.23；使用 Gradle Wrapper 构建，不能恢复手工 remap/refmap 构建流程。
- 构建产物、缓存、运行目录和测试数据留在项目内。不得修改开发者实际 Minecraft 实例的配置、模组或存档。
- 保留 holding、deferred vanilla spawn、每玩家独立状态、实际体型安全检测和客户端到达确认。世界状态和容量提交在 server thread 完成；可选兼容必须 fail-soft，搜索必须有预算。
- 改动后运行适当的自动检查；构建或 Mixin 修改需执行 `build` 和 `productionMixinSmoke`。测试限制必须如实记录。
- 不主动安装模组到外部实例、上传产物、推送或发布；这些操作需要用户在当前开发环境中的明确授权。
- 游戏内验证按实际变化权衡，日常优先构建与自动检查；不要频繁启动游戏，不要自动同意服务端 EULA。
