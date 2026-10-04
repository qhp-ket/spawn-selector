# 2.1.6 默认村庄描述 i18n

内置 `data/spawnselector/spawn_entries/village.json` 和 `default_spawnselector.json` 中的村庄描述统一引用 `spawnselector.entry.village.description`，保留白色样式。语言文件新增中文“在村庄外围寻找安全的地表出生点。”与英文“Find a safe surface spawn outside a village.”，旧翻译键继续保留。

具体实例此前已使用 `spawnselector.instance.name`、`spawnselector.instance.description`、`spawnselector.instance.capacity`／`unlimited`，不存在硬编码“村庄 1、2、3”的新文案。本轮 `tests/LocalizationTest.java` 扩展真实 `Packets.View` 序列化往返测试：同一份数据的地点 1、2、3 在中英文下分别解析成“村庄 · 地点 N”／“Village · Location N”，坐标与有限容量也正确翻译；默认村庄描述保留翻译键，并随客户端语言切换。

`gradlew.bat build --offline` 通过（1m12s，27 个任务）。本轮语言测试、配置读写测试、capacity、SavedData、Origins fail-soft、Perimeter、开发／生产 linkage 和 jar 结构检查均通过，详见 `build/validation-2.1.6.log`。仅涉及默认资源与语言测试，未启动游戏，也未重复运行上一版已通过的生产 Mixin 运行验证。

生产包 `spawnselector-2.1.6-forge-1.20.1.jar` 已安装到正式 mods，替换 2.1.5，无额外备份，当前只有一个活动 Spawn Selector。安装包 SHA256 与构建包一致：`e4273d152c5a3698d5d9c81e123f94f7bd11e2c18931ce2d77a455ea626f3e00`。记录见 `build/local-install/installation-2.1.6.json`。

正式实例的五个 Spawn Selector 配置文件前后 SHA256 全部一致。现有 `config/spawnselector/entries/village.json` 显式保存了固定中文描述，因此仍会覆盖新的内置默认。需要该已有选项使用新翻译时，由用户自行将 `description` 改成 `{"translate":"spawnselector.entry.village.description"}`，或在现有组件数组中将 `text` 替换为上述 `translate` 字段；样式可继续保留。
