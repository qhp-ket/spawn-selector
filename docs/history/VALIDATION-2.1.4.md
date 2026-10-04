# Spawn Selector 2.1.4

本轮在配置 GUI 增加简单名称编辑，不增加颜色或富文本编辑器。

- `SpawnConfigScreen.java`：“条目”页第一项为名称输入框，读取原有 Component 在当前语言下的文字。只在文字发生修改时转换为自定义文字；保持不变或在同一输入框内撤回修改时，保留完整原始 JSON 和翻译键。滚动、切换条目／页签、打开 JSON 和保存沿用原有草稿流程；保存失败后输入值仍留在界面中。
- `ConfigComponents.java`：名称字符串修改保留 Component 根样式（颜色、粗体、斜体、hoverEvent 等）。复杂多段名称改为一段，采用根组件／首段样式，GUI tooltip 与 README 说明此行为；未编辑的复杂名称不变。
- `ConfigFiles.java`：识别 GUI 的字符串改名后，将新文字应用到最新磁盘名称组件，保留并发外部编辑的整体样式。未修改的名称不提交改动，描述及未知条目字段继续保留；稳定条目 ID 不变。
- 中英文提示更新；README 更新操作和样式说明；默认配置仍只有原版村庄。

运行 `gradlew.bat build --offline`，包含开发／生产 jar linkage、refmap、原生 Component、配置文件、SavedData、Origins、capacity 和 perimeter 检查。新增验证覆盖未编辑名称保留翻译键、改名转为固定文字且保留样式、复杂名称未编辑时原样保留／编辑时根样式保留，以及真实 JSON 文件中 GUI 改名与外部颜色／hoverEvent 编辑的同时保存。

构建成功（1m 26s，25 个任务），开发／生产各 5044 个运行时引用检查通过。日志：`build/validation-2.1.4.log`。本轮没有启动游戏，没有修改整合包已有 config、世界或其他模组；GUI 实际键入、保存和游戏内名称显示仍可在下次正常启动时复核。之前的 holding、多人容量、Origins 和 deferred vanilla spawn 生命周期没有更改。

构建成功后生产包安装到父级 mods，直接替换活动 Spawn Selector，不新增备份。安装与正式配置字节校验记录在 `build/local-install/installation-2.1.4.json`。
