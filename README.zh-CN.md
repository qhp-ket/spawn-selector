# Spawn Selector

[English](README.md) | [简体中文](README.zh-CN.md)

[![构建与验证](https://github.com/qhp-ket/spawn-selector/actions/workflows/build.yml/badge.svg)](https://github.com/qhp-ket/spawn-selector/actions/workflows/build.yml)

为首次加入的玩家提供**结构外围安全出生点选择**。支持同一种结构的多个实例、每实例人数容量、自定义选项和可选 Origins 兼容。

**Minecraft 1.20.1 · Forge 47.4.23 · Java 17。** 服务端和所有客户端须安装相同版本。Origins 是可选兼容。

## 功能

- 新玩家在 holding 维度等待首次出生选择，限制移动和交互；目标在客户端准备完成前保持保护。
- 先选择结构类型，再选择具体实例；同服玩家复用服务器已发现的候选。
- 在结构整体占地范围之外出生，面向结构中心；按玩家实际尺寸和碰撞箱检查安全。
- 每实例可独占、限定人数或无限共享；已完成选择跨重启保存。
- 等待 Origins 自己完成种族选择；兼容 API 故障按玩家独立降级。
- 延迟无人使用的原版出生区域准备；玩家放弃自选时再初始化原版出生点。

## 安装与构建

安装 JDK 17 和 Python 3.8+，设置 `JAVA_HOME`，确保 Python 在 PATH 中。无需现有 Minecraft 实例或系统 Gradle，首次构建会下载依赖。

Windows：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```sh
./gradlew build
```

将 `build/libs/spawnselector-2.1.7-forge-1.20.1.jar` 放入游戏实例的 `mods`，只保留一个活动版本。`-dev.jar` 使用开发映射，`-thin.jar` 是构建中间产物。

Wrapper 下载有 SHA256 校验，默认缓存位于 `.gradle-home`，尊重 `GRADLE_USER_HOME` 和 `--gradle-user-home`。Python 默认命令在 Windows 为 `python`，其他平台为 `python3`；可通过 `-PpythonExecutable=/path/to/python` 或 `PYTHON` 指定。

开发运行使用 `runClient` 或 `runServer`。服务端 EULA 由使用者自行同意。更多开发说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 配置

内置默认只有**村庄**，目标为 `#minecraft:village`。在 Forge Mods 配置界面保存，或使用“打开 JSON”按钮，会生成：

```text
config/spawnselector/
├── settings.json
└── entries/
    └── village.json
```

GUI 可编辑名称和参数、预览图标与描述、选择结构 ID 或标签，并打开对应 JSON。描述、颜色和翻译键在 JSON 中编辑。GUI 改名后成为固定自定义文字；没有修改时保留原翻译键。

手动配置时，`settings.json` 用于启用分文件格式：

```json
{"schema_version": 2, "layout": {}}
```

例如，`entries/village.json` 可覆盖内置村庄选项：

```json
{
  "schema_version": 2,
  "id": "spawnselector:village",
  "target": "#minecraft:village",
  "candidate_count": 3,
  "capacity_per_instance": 2,
  "min_distance": 8,
  "max_distance": 48,
  "require_open_sky": true,
  "exclusions": []
}
```

| 字段 | 含义 |
| --- | --- |
| `target` | 结构注册 ID 或 `#结构标签`。 |
| `candidate_count` | 最多发现多少个不同实例，1–32，默认 3；实际可能更少。 |
| `capacity_per_instance` | 每实例 1–10000 人，或 `-1` 无限共享，默认 `-1`。 |
| `min_distance`、`max_distance` | 相对结构 bounding box 的外围搜索距离，默认 8 和 48 格。 |
| `require_open_sky` | 是否要求上方露天，默认 `true`；关闭后其他安全检查仍然生效。 |
| `exclusions` | 结构 ID、`mymod:ruined_*` 等通配符 ID，或 `#标签`。 |

外部配置字段覆盖内置或数据包定义；GUI 保存会写出完整选项。使用 `enabled: false` 禁用选项，删除内置选项的配置文件则恢复默认。重命名文件不改变 `id`；改变 `id` 会成为不同条目。

多人以**服务端配置和数据包**为准。远程客户端不能通过本地编辑器保存服务端配置。修改服务端文件后，由有管理权限的人执行 `/reload`，或重新启动世界。

在新 settings 尚不存在时，旧 `config/spawnselector.json` 仍可读取。首次 GUI 保存会迁移，保留旧文件和已有自定义文字。

数据包选项位于 `data/<namespace>/spawn_entries/<path>.json`，文件资源位置即条目 ID。布局覆盖位于 `data/spawnselector/spawn_ui/layout.json`。

## 多人和存档

Claim 在服务器线程检查并提交。取消、准备失败、到达前掉线会释放临时 claim；重启恢复中断的预留。完成首次出生后永久占用容量，死亡和掉线也不释放。管理员 `/spawnselector reset` 不回收已完成容量。

同一实例的多个选项别名共享 claim，采用最严格的有限容量。降低容量不会驱逐玩家。候选缓存跨重启保留；影响发现的配置改变会使相应发现缓存失效。每位新玩家仍按自身尺寸重新检查安全。

SavedData 升级保留旧玩家的已完成选择，不强迫重新选择。

## 文案和翻译

名称与描述使用 Minecraft 原生 JSON 文本组件。固定文字不翻译，`translate` 引用语言键；数组和 `extra` 可混合样式与翻译段落：

```json
{
  "description": [
    {"translate": "spawnselector.entry.village.description", "color": "white"},
    {"text": "\n自定义提示", "color": "gold", "bold": true, "italic": true}
  ]
}
```

内置中英文覆盖界面消息、默认村庄名称和描述、地点编号、坐标和人数提示。每个客户端按自己的语言显示，配置中的固定文字继续保持固定。

自定义或覆盖翻译使用标准客户端资源包，`pack_format: 15`，语言文件如 `assets/spawnselector/lang/en_us.json` 和 `zh_cn.json`。选项中用 `{"translate":"yourpack.spawn.description"}` 引用自己的键。F3+T 重载客户端资源；修改选项配置还需服务端 reload。修改 config 不会修改语言文件。

## 验证和限制

Windows、Ubuntu CI 会构建两种产物、编译集成测试，并执行生产 Mixin smoke 检查。自动检查覆盖容量、SavedData、配置、语言、Origins 降级、外围几何、linkage 和 jar 内容；干净源码与初始空缓存构建也已通过。

发现和地形准备均有预算上限。未知自定义 placement 降级为原版单实例定位。不支持 ceiling 维度，以及宽超过 8 格或高超过 16 格的玩家。独立服务端完整世界与真实双客户端多人回归仍待进行，自动检查不替代这些测试。

## 许可和来源

[GNU AGPL-3.0-only](LICENSE)。保留旧 MIT 和第三方许可声明。

结构过滤与 biome viability 思路参考 BetterVillageSpawnPoint 的 CC0-1.0 源码，并独立实现。来源、Gradle Wrapper 和 MixinExtras 声明见 [NOTICE.md](NOTICE.md)。
