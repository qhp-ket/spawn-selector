# Spawn Selector 2.1.3

本轮仅调整 GUI、内置默认选项及兼容加载。holding、Origins、candidate pool／capacity、传送保护和 deferred vanilla spawn 的生命周期没有修改，网络协议仍为 6。

## 修改

- `SpawnSelectionScreen.java`：黑色面板上下内部留白约 12 px；标题、卡片和底部按钮一起重新定位。提示仍相对实际按钮顶部定位，兼容返回重选和窄窗口的三排按钮；小窗口卡片数量自动减少，描述滚动保留。
- `SpawnConfigScreen.java`：表单和列表增加上方留白，表单滚动提示预留独立空间并保持在黑色面板内。左侧每个选项及图标字段旁显示物品图标，图标输入实时预览；未注册或 air 图标显示红底指南针和说明，保留原输入。
- `IdentifierSuggestions.java`：模组、物品、维度 ID 的本地补全，支持命名空间前缀或路径前缀、键盘选择／接受／收起和鼠标点击／滚动。最多保留 128 个匹配、同时显示 6 行，靠近窗口底部时向上展开。物品列表来自客户端物品注册表，模组列表来自 ModList，维度列表来自当前服务端；主菜单仅提供原版维度，未知 ID 允许手动输入。不会发送指令或生成区块。完整合法 ID 默认不弹窗，Ctrl+Space 可重新请求建议。
- `ConfigFiles.ensureDirectory()` 与 GUI 目录按钮：总是创建并打开 `config/spawnselector`，不退回 config 父目录，不保存草稿或启动迁移。目录按钮 tooltip 显示实际完整路径。
- `default_spawnselector.json` 和 `data/spawnselector/spawn_entries/village.json`：默认仅有 `#minecraft:village`，名称“村庄／Village”；描述不再假定有传送石碑模组。删除三个非原版选项的自动注册资源。
- `legacy_spawn_entries.json`、`ExternalConfig.resolve()`、`SpawnEntries.java`：只为外部配置中明确存在的三个历史 ID 提供缺失字段。数据包有同 ID 时优先作为基础，外部配置继续覆盖；无配置的旧选项不自动恢复。这使实际整合包中仅包含 order 的酒馆／修道院简写仍可加载，且不改写原配置。
- 中英文语言文件新增补全操作、维度信息限制、未知图标和目录错误说明；README 更新默认配置和操作方式。

## 自动验证

执行 `gradlew.bat build compileIntegrationJava productionMixinSmoke --offline`。检查覆盖：

- 真实配置文件迁移／并发外部文案与 GUI 参数保存／重复 ID 和未来 schema 防覆盖。
- 全新配置只有村庄；历史简写只恢复明确列出的选项，自定义 target 保持；打开目录不生成 settings 或触发迁移。
- ID 补全的跨命名空间路径前缀、显式命名空间限制、匹配数量上限和未知输入。
- 10000 随机 perimeter 几何、容量 1／2／-1、真实 NBT 状态迁移／恢复、Origins 异常隔离、Component 编解码及中英文资源。
- 开发／生产 jar linkage、Java 17、默认资源一致性、只注册村庄、AGPL 文件、AP refmap 和生产 Mixin 转换。
- 游戏测试代码编译。

最终命令成功（1m 28s，27 个任务）；开发和生产包各通过 5014 个运行时引用的 linkage 检查。日志：`build/validation-2.1.3-final.log`。生产 Mixin smoke 不创建世界、不打开游戏窗口；本轮没有运行 runClient／runServer，没有改正式 config、saves 或其他模组。

## 游戏内复核

请用正常启动方式检查配置 GUI 的图标与补全，尤其 GUI 缩放较大时的弹窗位置、Tab 接受／Esc 收起，以及“配置目录”打开的位置；进入世界检查定位完成后的返回重选、提示文字与按钮留白。本轮没有宣称这些渲染和输入交互已在实际游戏中验证。

生产包放入父级 mods，直接替换旧活动 Spawn Selector，保持只有一个活动版本，不增加备份或 disabled 副本。安装的 SHA256 与构建产物核对，记录见 `build/local-install/installation-2.1.3.json`。正式旧配置字节校验也记录于该文件。
