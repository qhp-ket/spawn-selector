# Spawn Selector 2.1.1 修改与验证

2026-10-04；Minecraft 1.20.1 / Forge 47.4.23 / Java 17。本轮依用户要求权衡测试，没有启动客户端、服务端世界或 GameTest 世界，也没有修改正式 mods/config/saves。

## 实现

- LICENSE 换为从 GNU 官方下载的完整标准 AGPLv3 正文，标识 AGPL-3.0-only；原 MIT 声明保留于 LICENSES/MIT-legacy.txt，来源见 NOTICE.md。开发/生产 jar 均包含这些文件，mods.toml 同步修改。
- SpawnSelectionScreen：提示/页脚的位置按实际最上方按钮计算，实例页返回按钮额外占一行时自动上移；消息/页脚支持最多两行，卡片数量/描述区域按剩余空间调整，预览也受提示区域约束。没有修改此前的 dirt 背景或暂停/加载生命周期。
- Packets.View.message 改为 Component，协议 5 → 6，服务端不再将内置进度/完成消息提前解析为中文；嵌套条目名称、容量及坐标参数均保留到客户端翻译。
- assets/spawnselector/lang/en_us.json 与 zh_cn.json：126 个对应翻译键，覆盖界面、配置、默认条目、状态、失败及命令消息。用户自定义 text 保持原样；技术异常详情仍保留原始诊断文本。
- ConfigComponents：未编辑的 Component 保留 translate 和样式。配置开关使用布尔值，rating presets 保存翻译键。远程连接时保存按钮禁用，客户端不能通过选择协议写入服务端配置。
- StructureCandidatePool / SpawnSelectorManager：在 schema 2 中添加可选 completed_discoveries 元数据，不影响旧完成玩家/候选/claims。正常完成的少量/空发现结果跨玩家与重启复用；缓存指纹包含目标、维度、半径、数量、exclusions 和实际解析出的结构 IDs。容量变化不要求新定位。异常/超时/失败 chunk future 不记录永久完成缓存。
- MultiStructureLocator：标记发现是否正常完成。原预算、STARTS 阶段、FULL=0、未知 placement fallback 和服务器线程约束保留。
- runtime_test.py：选择当前版本的生产 jar，允许 build/libs 保留旧版本产物。

## 本轮实际检查

命令：`gradlew.bat build compileIntegrationJava productionMixinSmoke --offline`。

首次完整检查日志：[build/validation-2.1.1.log](../../build/validation-2.1.1.log)，BUILD SUCCESSFUL in 1m 14s，包含 productionMixinSmoke。

最后的英文标签/提示宽度调整后再次执行 `build compileIntegrationJava --offline`：[build/validation-2.1.1-final.log](../../build/validation-2.1.1-final.log)，BUILD SUCCESSFUL in 1m 1s，25 actionable tasks。没有再次启动游戏或重复未变化的生产 Mixin 转换检查。

- capacityTest：1/2/-1、最后容量槽位、释放、永久 claim、稳定实例身份通过。
- perimeterTest：10000 随机几何/宽大 footprint/顺序/预算通过。
- savedDataTest：旧玩家迁移、NBT 往返、临时 claim 恢复、未来 schema 保护通过；新增 completed discovery 持久化和配置指纹失效检查通过。
- localizationTest：真实 FriendlyByteBuf 编解码消费完整 payload；生命周期字段/token 保留；同一 decoded message 先后在英文/中文解析；名称/位置/容量嵌套参数保留；未编辑配置保留 translate/style，自定义文字仍是 literal；默认条目解析通过。此测试只运行 JVM，无游戏世界。
- linkageAudit / productionLinkageAudit：最终开发、生产各 4525 个 runtime Minecraft/Forge/selector 引用通过。
- verifyArtifacts：两个 jar 的 Java17、AP refmap、默认 JSON、一致语言键与参数、所有静态翻译键、AGPL/旧 MIT/NOTICE 打包验证通过。
- compileIntegrationJava：原游戏内回归工具可编译；没有实际运行这些世界测试。
- productionMixinSmoke：仅类转换，无世界/登录/渲染；生产 SRG hooks 与非阻塞 chunk invoker 通过。

ResourceLocation 等 Forge API 的 deprecated/removal 编译警告保留，本轮没有为清理警告进行 API 大改。Origins 单元测试的异常 warning 是预期注入，并非实际运行兼容失败。

最终生产 jar：`build/libs/spawnselector-2.1.1-forge-1.20.1.jar`，SHA256 `633AA761F54A7F744ADFAC93F90CA1C116B6EF02655B04DBE854EE0CEFA4EF7B`。

最终开发 jar：`build/libs/spawnselector-2.1.1-dev.jar`，SHA256 `835F38159BE45015FDBC73004CE75358DBD3FCAFFFD5B3A824F19361C47FA334`。

## 多人和配置结论

服务器共享候选池和发现任务；新玩家不需要重新定位已有结构。数量不足的正常结束搜索现在也持久化。选择后读取具体 StructureStart 和按当前体型安全搜索是必要准备，不是重做大范围 locate。

服务端读取自己的外部配置/数据包，发送可选卡片及 Layout，重新校验客户端选项。客户端 Action 只有选项 ID/实例 token/确认动作，没有配置写入字段或出生坐标。容量原子性仍依靠服务器线程串行检查与提交，Session 仍按 UUID 独立。客户端语言差异不影响世界规则。

异常/超时搜索不成为永久完成缓存；在同一次服务器运行中原共享任务仍会保留已结束结果，重启或修改搜索配置可重新尝试。改变世界生成内容但保持同样 structure IDs 的复杂数据包变更，缓存不能完全识别；若需重搜，可调整 search_radius/candidate_count 等搜索设置。

## 验证边界 / 手测

本轮未做 GUI 截图或实机视觉验收，也未重跑上一轮八项实际世界测试；这些历史证据见 [VALIDATION-2.1.0.md](VALIDATION-2.1.0.md)。后续正常试玩时重点看中文/英文、较大 GUI scale 下实例页两行按钮与提示，以及配置编辑后默认条目翻译是否保持。

Dedicated server / Alice-Bob 真实同时联网的既有未验收项仍保留；本轮没有自动接受 EULA。新旧两端不能混用：2.1.1 使用协议 6，需要客户端和服务器同步更新。
