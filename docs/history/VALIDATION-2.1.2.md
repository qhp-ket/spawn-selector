# Spawn Selector 2.1.2 — 配置、标签和界面

日期：2026-10-04。目标仍为 Minecraft 1.20.1、Forge 47.4.23、Java 17，许可证 AGPL-3.0-only，网络协议继续为 6。本轮开发、构建产物和文件测试全部位于开发目录；验证结束时尚未安装到正式 mods。随后用户授权了本地安装，记录见末尾补记。正式 config、saves 和远端仓库未修改。

## 配置与外部文本编辑

- 新增 [ConfigFiles.java](../../src/main/java/dev/tide/spawnselector/ConfigFiles.java)，负责共用的文件加载、稳定 ID、迁移和写入。读取配置没有写入副作用。
- [ExternalConfig.java](../../src/main/java/dev/tide/spawnselector/ExternalConfig.java) 继续作为运行时和 GUI 的共同入口；损坏文件会报告错误，GUI 禁止覆盖。运行中读取失败时保留上一份成功读取的覆盖配置；首次读取失败使用默认／数据包定义。
- 新格式为 `config/spawnselector/settings.json` 和 `entries/*.json`，配置 schema 2。settings 包含全局 layout，每个选项文件包含明确的 id、schema_version 和完整定义；其他命名空间的新文件放在 `entries/<namespace>/<path>.json`。
- 旧 `config/spawnselector.json` 在新 settings 尚不存在时继续读取。首次 GUI 保存／打开 JSON 才迁移，旧文件原封不动保留；新 settings 存在时旧配置不再参与合并。
- 文件名与选项身份分离：重命名文件不改变选项 id，后续保存继续使用该文件。重复 ID、未来版本、越界路径和外部新建选项与 GUI 草稿同 ID 的冲突，在替换文件前拒绝。
- 保存先重读磁盘，只把 GUI 相对其打开时基线的改动应用到最新配置，保留外部编辑的文本组件及未知字段。完整文本组件整体覆盖，避免固定 text 和旧 translate 混在一起。
- 默认／数据包定义仍是底层，外部显式字段优先。GUI 保存会物化完整配置，因此保存后的文件字段会覆盖数据包；这是完整选项文件的明确语义。禁用内置选项使用 enabled=false，单纯删文件会回退默认／数据包定义。
- 写入先校验和临时写出所有目标文件，再逐文件替换；settings 在首次迁移时最后启用。I/O 失败尝试恢复已替换文件；没有修改世界 SavedData 的保存机制。

## GUI

[SpawnConfigScreen.java](../../src/main/java/dev/tide/spawnselector/SpawnConfigScreen.java) 提供条目／搜索／文案／全局四页。参数为带独立标签的滚动表单，侧栏选择条目并排序；小窗口也可以访问全部字段。数字输入和参数先保存在草稿，最终写文件时检查整体有效性；保存失败不会清掉正在修改的输入。

名称和描述不再提供固定评级、正文、高亮编辑框，也没有片段编辑器。文案页只读显示实际带样式组件，以及 i18n／自定义／混合类型，支持滚动阅读。

“打开选项 JSON／打开全局 JSON”先保存参数草稿，再由系统默认应用打开文件；按钮 tooltip 明确说明保存行为。“配置目录”可在尚未迁移时打开父级 config 目录。“重读配置”丢弃未保存的参数草稿并重读磁盘；游戏中使用 `/reload` 才更新服务器运行定义。远程连接时本地保存／打开 JSON 的写入操作禁用，配置仍以服务端为准。

[SpawnEntryOptionsScreen.java](../../src/main/java/dev/tide/spawnselector/SpawnEntryOptionsScreen.java) 保留容量、安全和排除项，改为统一宽度、标签在输入框上方，避免英文标签和字段重叠；resize 保留输入草稿。

## 默认文案与 i18n

- 默认选项中仅村庄名称使用 `spawnselector.entry.village.name`，中文“村庄”、英文“Village”。
- 其他默认名称和全部默认描述是固定中文组件，保留颜色／粗体并加入自然换行，用户可直接修改 JSON。
- 界面、状态、错误和命令消息继续中英文翻译。旧条目翻译键仍保留，兼容旧配置；没有强制改写用户文案。
- [ConfigComponents.java](../../src/main/java/dev/tide/spawnselector/ConfigComponents.java) 仅做预览和类型识别，不再将显示结果转成文本写回。
- [README.md](../../README.md) 写明原生 text/translate/extra 样式、自定义资源包目录、Minecraft 1.20.1 pack_format 15、两种语言示例、F3+T 与服务端 /reload 的区别。语言文件每种语言一个 JSON，未引入自定义语言加载器或 GUI 语言编辑入口。
- 上一轮未交付的 ConfigTextScreen / ConfigTextDocument 草稿及编辑器专用语言键已经移除；生产包和开发包检查确认没有残留这两个类。

## 结构标签

[StructureCatalog.java](../../src/main/java/dev/tide/spawnselector/StructureCatalog.java) 和 [StructurePickerScreen.java](../../src/main/java/dev/tide/spawnselector/StructurePickerScreen.java) 在原选择器中加入结构／标签／全部筛选。列表分别显示完整 target、来源、类型和世界内已知的标签成员数，行内文字不会互相覆盖。

世界内从结构 registry 读取结构及 getTags()；主菜单扫描已加载模组中的 worldgen/structure 和 tags/worldgen/structure 资源，并补充原版结构与 12 个原版结构标签。主菜单中的成员数未知时不虚构数量，未加载世界的数据包标签仍允许手动输入。

确认标签保存 `#namespace:tag`，标签不自动填入某个 mod 作为 required_mod。原有服务端 tag 解析、过滤、viability 和多实例发现继续工作，没有为标签引入独立 locator 或提前生成区块。

## 出生选择界面

[SpawnSelectionScreen.java](../../src/main/java/dev/tide/spawnselector/SpawnSelectionScreen.java) 保留当前 dirt 背景、选择／忙碌阶段的暂停语义、加载遮罩和服务器 action。

- 标题、卡片、描述、提示、按钮有独立留白和区域；分页容量为描述预留空间。
- 长描述使用真实 styled Component 换行并可滚动，带滚动条；预览图限制在描述区域内。
- 提示和页脚始终以最上方实际按钮为基准。窄面板自动上下排列确认／放弃，实例页返回按钮继续在它们上方。
- 卡片长名称提供 tooltip，状态提示可悬停查看完整内容。
- 翻页同步选择新页第一项，resize 根据实际选中项重算页码，避免“显示另一页却确认旧项”。

## 验证

最终命令：

```powershell
.\gradlew.bat build compileIntegrationJava productionMixinSmoke --offline
```

结果：**BUILD SUCCESSFUL，1m 29s，27 tasks**。日志：[build/validation-2.1.2-final.log](../../build/validation-2.1.2-final.log)。最初完整构建在静态语言键检查识别到动态前缀后失败，已改为明确键并完成最终构建。

- capacityTest：容量 1／2／-1、最后槽位的服务器线程顺序、释放、重启、永久 claim、稳定身份。
- configFilesTest：项目 build 目录下真实文件读写，旧无命名空间 ID 正规化、迁移、旧文件不变、改文件名不改 ID、外部复杂文案与 GUI 参数同时变动、未知字段保留、空修改不重写、禁用、损坏的 disabled 条目、外部新 ID 冲突、重复 ID、路径穿越、未来 schema、结构／标签资源路径。
- localizationTest：真实 FriendlyByteBuf/Component 往返、嵌套翻译参数、同一消息按中英文解析、原生样式和换行、只读预览、默认只有村庄名称 i18n。
- perimeterTest：10000 随机 footprint、负坐标、大型结构、优先边、唯一性和预算。
- savedDataTest：真实 NBT 旧 schema 迁移、候选持久化、未完成 claim 恢复和未来 schema 保护。
- originsCompatTest：异常分类、有限降级、UUID 隔离和重连。日志中的 Deliberate failure 是测试刻意注入。
- dev/prod linkageAudit：两包各 4712 个 Minecraft／Forge／selector 运行时引用成功解析。
- verifyArtifacts：Java 17、AP 生成 SRG refmap、JSON、默认资源一致性、中英键／占位符、AGPL 与来源声明、不包含测试 mod 或依赖。
- compileIntegrationJava：世界测试 harness 编译通过；本轮未执行世界测试。
- productionMixinSmoke：退出 0，生产 SRG Mixin 实际转换成功，初始 spawn／prepareLevels 等 hooks 有实际调用点，非阻塞 chunk invoker 转换成功。它只做类转换，不创建世界、不登录、不启动 GUI。
- README 中所有 JSON 示例另行解析通过。

## 产物

生产包 `build/libs/spawnselector-2.1.2-forge-1.20.1.jar`：209499 bytes。

SHA256：`21565FB9FA5BA4059AEC40DE885298D07038D2D001BBF07B1B8B149B9554ACDE`

开发包 `build/libs/spawnselector-2.1.2-dev.jar`：208162 bytes。

SHA256：`64B8FD7D43D2736352A259FC57CCC73707DEC047EF03B6D3FC1017EBCDE38FC1`

## 保留与手测边界

holding、每玩家 Session、Origins optional/fail-soft、多实例候选池及服务器线程 capacity、玩家 UUID 持久化、外围真实体型安全检测、FULL future/ticket、客户端 chunk/mesh ACK 和 deferred vanilla spawn 生命周期都沿用现有实现。没有修改玩家 SavedData schema；配置 schema 2 与玩家 SavedData schema 2 是不同系统。

本轮没有 runClient／runServer，也没有接受服务端 EULA。真实世界／Origins 回归仍以 [VALIDATION-2.1.0.md](VALIDATION-2.1.0.md) 的既有证据为准，本轮不宣称已经手测新 GUI 或真实双客户端。

下次正常游戏时建议集中确认：中英文与不同 GUI scale 的配置页／选择页；系统文件关联能打开正确 JSON；打开后外部编辑文案、重读和 /reload；主菜单与世界内 #minecraft:village 标签；长描述滚动、实例页返回／确认／放弃的间距；缩放窗口和翻页后确认的确是当前选中的实例。自定义翻译资源包的启用与 F3+T 也需实际客户端验证。

## 本地安装补记

用户随后明确要求今后构建成功后把生产 jar 放入正式 mods。已安装 `../spawnselector-2.1.2-forge-1.20.1.jar`，SHA256 与上述生产构建包一致。安装前解析所有顶层活动 jar 的 mods.toml，未发现旧的活动 Spawn Selector；安装后确认仅一份 modId=spawnselector 的活动包。历史 disabled 文件保留，没有启动游戏。安装记录在 `build/local-install/installation.json`；后续本地交付约定已写入 AGENTS.md 和 README。
