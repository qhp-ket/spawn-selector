# Spawn Selector 2.1.7

为首次加入的玩家提供结构外围安全出生点选择。支持同一种结构的多个实例、每实例人数容量、可配置条目和可选 Origins 兼容。玩家完成首次出生后保留选择状态。

Forge 1.20.1 / Forge 47.4.23 / Java 17。Origins 可选，无编译或运行硬依赖；单人和服务端共用生命周期。两端须装相同版本，网络协议为 6。缓存、测试运行配置、测试世界和构建产物默认留在项目内。

## 构建与运行

安装 JDK 17 和 Python 3.8+，设置 `JAVA_HOME` 并确保 Python 在 PATH；不需要已有 Minecraft／HMCL 实例或系统 Gradle。在克隆的项目目录运行：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat productionMixinSmoke
```

Linux／macOS 使用 `./gradlew` 执行相同任务。Gradle Wrapper 8.8、ForgeGradle 6.0.54、Java toolchain/release 17；Wrapper 验证下载 SHA256，默认缓存在项目 `.gradle-home`，尊重显式 `GRADLE_USER_HOME` 或 `--gradle-user-home`。首次构建需网络，依赖齐全后可加 `--offline`。

构建附带的 JSON/jar 完整性检查使用 Python；编译、Mixin AP 和 reobf 均由 Gradle 完成。Windows 默认 `python`，其他平台默认 `python3`，可通过 `-PpythonExecutable=/path/to/python` 或 `PYTHON` 环境变量指定。

生产包：`build/libs/spawnselector-2.1.7-forge-1.20.1.jar`；开发包：`build/libs/spawnselector-2.1.7-dev.jar`。Mixin AP 生成 refmap，ForgeGradle 负责 reobf。2.1.5 起生产包由 `jarJar`／`reobfJarJar` 生成，开发包也携带同一份 MixinExtras 0.5.5 嵌套依赖及版本元数据，独立安装无需另找模组提供该库。`-thin.jar` 是构建中间产物，不用于游戏安装。

游戏安装时将生产 jar 放入所需实例的 `mods`，确保只有一个活动 Spawn Selector 版本。Gradle 构建只输出产物，不安装或覆盖外部实例。参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)。

2.1.7 已通过干净源码副本与空依赖缓存的独立构建和生产 Mixin 验证，记录及测试限制见 [公共仓库验证](docs/VALIDATION-2.1.7.md)。GitHub Actions 提供 Windows／Ubuntu 检查；源码公开和构建通过不代表所有多人实机场景已验收。

runClient 使用 `run/client`；runServer 使用 `run/server`。服务器 EULA 必须由使用者同意，项目不会自动接受。`productionMixinSmoke` 使用 ForgeGradle 的 SRG 中间产物、运行依赖和 userdev 启动元数据，独立检查生产包转换及实际分支，不读取其他游戏实例，不创建世界或游戏窗口。

## 首次出生与保护

新玩家进入 holding 虚空维度，已完成的 UUID 不重复选择。冻结、免伤、交互和物品限制、holding 内碰撞限制保留。Origins 未完成时等待真实 API 状态，不抢其界面；API shape/linkage 故障告警后降级，普通调用异常每玩家最多三次、间隔两秒，然后仅绕过该玩家本次会话。正常未选种族不会被超时放行。

选择结构类型，再选择具体实例。发现阶段仅请求 STRUCTURE_STARTS；claim 成功后才请求 FULL 地形，寻找 StructureStart 整体 bounding box 外围的安全位置，不扫描 pieces。传送朝向结构中心并设置个人强制重生点。

传送后维持保护和目标 ticket；客户端检查维度、位置、区块接收、中心 mesh 编译，并携带本次 token 确认。400 tick 未确认只告警和重发，不自动解冻。已到达而未确认的玩家重连继续确认。若崩溃使已完成 SavedData 与仍在 holding 的旧玩家 NBT 不一致，登录恢复记录中的结构出生点和个人重生点，再等待客户端确认。

MinecraftServerMixin 对初始 spawn 搜索以及 prepareLevels 的 START / 441 区块等待保持延迟；holding 本身不能替代它。只有“放弃”才 materialize 原版出生点并恢复 START ticket，一次性状态持久化。

2.1.5 的生产源码不再使用 `@Redirect`。START ticket 用 `WrapWithCondition` 控制，等待循环用表达式 `ModifyExpressionValue` 修改是否继续等待，不伪造区块数量，也不把要求改成 0；正常准备保留原比较结果。`prepareLevels` 后半段的原版／Forge 持久强制区块恢复、进度结束和生物生成标记仍执行。登录维度和待选择玩家的床位置使用返回值调整，构造器出生位置和重生 fallback 使用 `WrapOperation`；正常分支调用传入的 operation，holding 构造分支避免执行共享出生点及潜在高度图读取。构造器包装保持静态并位于父构造之前。可组合注入能降低同调用点独占风险，不能保证兼容所有重写同流程的模组。

## 配置

2.1.2 支持分文件配置。首次在 Forge Mods 配置 GUI 中保存，或点击“打开选项 JSON／打开全局 JSON”，会生成以下目录。只读加载不会创建文件；存在旧 `config/spawnselector.json` 时，先读取旧配置，首次保存才迁移，并完整保留旧文件。

```text
config/spawnselector/
├── settings.json
└── entries/
    └── village.json
```

`settings.json` 包含 `schema_version: 2` 和 `layout`。每个选项文件包含 `schema_version: 2`、稳定的 `id` 及该选项的完整定义。其他命名空间的新选项按 `entries/<namespace>/<path>.json` 创建。重命名文件不改变 ID；修改 ID 则属于另一个选项，会影响候选池关联，请谨慎。重复 ID、损坏 JSON 或未来 schema 会报告文件和错误，并禁止 GUI 覆盖。

`settings.json` 是新格式启用标记：存在时只读取新目录，旧文件不再参与合并。手动创建分文件配置时也须提供它。运行时仍保留“模组默认／世界数据包 → 外部配置”的优先级；外部配置中显式写出的字段覆盖数据包。GUI 保存会将默认值和已有配置物化为完整选项文件，之后这些文件中的字段具有配置优先级。禁用默认选项使用 `enabled: false`；只删文件会重新使用默认／数据包定义。

手动创建时最小的 `settings.json` 如下，布局缺省字段使用内置／数据包值：

```json
{
  "schema_version": 2,
  "layout": {}
}
```

GUI 的条目、搜索、全局参数可编辑，文案页提供带样式的预览，显示 i18n／自定义／混合状态。“条目”页第一项可直接编辑名称字符串：不修改时保留原始翻译键与 JSON；改名后成为固定自定义名称，保留原有整体颜色和样式。复杂分段名称会合并为一段，使用根组件／首段样式。名称编辑只改显示文案，不改变稳定 ID、目标或候选池。“打开 JSON”仍用于描述、颜色、富文本和翻译键，不提供富文本编辑器；按钮会先保存当前草稿，再调用系统默认程序。无编辑器关联时可打开配置目录自行选择程序。“重读配置”会丢弃未保存的 GUI 草稿并重新读取文件，游戏内生效仍需 `/reload` 或重新进入世界。GUI 保存前重读最新文件，只应用 GUI 改动；改名会保留文件里最新的整体样式，其他未编辑的文本组件和未知条目字段继续保留。

参数使用分页和滚动表单，较小窗口也可访问全部选项。结构选择器支持“全部／结构／标签”筛选和来源搜索：世界内读取注册表的标签及成员数量，主菜单扫描模组结构和结构标签资源，并列出原版结构标签。主菜单不能获知未加载世界的数据包专属标签，允许手动填写 `#tag`，最终以服务端解析为准。选择标签不自动附加某个模组为硬依赖。

配置 GUI 的左侧选项列表和图标参数旁显示物品预览；未知或空物品用红底指南针提示，不改写输入。模组 ID、物品图标 ID 支持已加载注册信息的补全，维度 ID 在世界内使用服务端提供的维度列表，主菜单仅列原版维度。输入完整 ID 或不带命名空间的路径前缀，方向键选择、Tab／Enter 或点击接受、Esc 收起，Ctrl+Space 重新打开；未列出的 ID 仍可手动输入。“配置目录”总是打开 `config/spawnselector`，若不存在只创建目录，不保存草稿、不触发配置迁移。

多人使用服务器的配置/数据包：服务器发送可用条目、实例卡片和完整布局，客户端只提交服务器提供的选项 ID/实例 token，不能上传自己的容量或坐标。远程连接时本地编辑器不可保存；管理员须修改服务器端配置并 reload。客户端语言可以不同，这不会改变容量或配置。

```json
{
  "schema_version": 2,
  "id": "mymod:fort",
  "name": {
    "text": "我的要塞"
  },
  "description": {
    "text": "在结构外围出生"
  },
  "icon": "minecraft:stone_bricks",
  "dimension": "minecraft:overworld",
  "locator": "minecraft_structure",
  "target": "mymod:fort",
  "required_mod": "mymod",
  "order": 70,
  "search_radius": 32,
  "min_distance": 8,
  "max_distance": 48,
  "preferred_side": "north",
  "candidate_count": 3,
  "capacity_per_instance": 2,
  "require_open_sky": true,
  "exclusions": [
    "mymod:ruined_*",
    "#mymod:unsafe_forts"
  ]
}
```

target 支持 ID 或 #tag；exclusions 支持 ID、ID 中的 * 通配符、明确 tag。candidate_count 范围 1–32，默认 3；capacity_per_instance 为 1–10000 或 -1，默认 -1。1 独占，-1 无限共享，不需要 allowSameStructure。旧配置缺字段自动使用默认值。

search_radius 1–100；min_distance 4–64；max_distance min–128；side 为 north/east/south/west。保留原来不支持 ceiling 维度的规则。require_open_sky 默认 true，false 允许有顶但其他安全检查仍通过的位置。

数据包条目 `data/spawnselector/spawn_entries/<id>.json`；布局 `data/spawnselector/spawn_ui/layout.json`。2.1.3 起内置默认只有原版村庄（`#minecraft:village`），名称为“村庄／Village”，描述不假定安装传送石碑等模组。旧配置中明确列出的修道院、酒馆或博物馆仍支持简写覆盖：`legacy_spawn_entries.json` 仅为这些已有 ID 提供缺省字段，不自动增加选项，不改写原配置。新的模组选项请自行添加配置或数据包。明确不能生成的目标隐藏；自定义 biome source/structure 无法可靠判断时仍允许 locate。

## 容量与存档

稳定身份为 dimension + registry structure ID + start chunk。claim 在 server thread 原子检查并写入，与玩家记录共用 SavedData 保存单元，立即 setDirty。

返回、取消、传送前失败、未完成时掉线释放临时 claim；重启清理无活跃 Session 的临时 claim。正式到达即永久占用，客户端确认决定解冻；死亡、掉线、重启不释放完成 claim。管理员 `/spawnselector reset` 也不回收永久容量，同一玩家可在不同实例留下历史 claim。

同一实例的多个配置别名共享 claim，采用这些选项中最严格的有限容量，防止绕过独占。降低容量不驱逐已完成玩家。prepared spawn 保存，但新玩家按自身真实体型重新验证和搜索。

同服玩家共享发现任务和 SavedData 候选池，候选数量足够时直接复用。2.1.1 还保存正常完成的发现记录：即使最终少于 candidate_count 或未找到实例，后来加入的玩家及重启后也不会重复相同扫描。修改 target/dimension/search_radius/candidate_count/exclusions 或 tag 解析出的结构集合会更新缓存指纹；只修改容量不触发重定位。异常或超时不记录为永久完成缓存。已有候选仍保留，选中时仅读取对应 start 并做当前玩家的安全检测。

spawnselector_players.dat schema 2 保存玩家 entry/dimension/spawn_pos/state/structure identity，以及候选 state/capacity/claims。schema 0/1 已选玩家迁移为 LEGACY_COMPLETE，不强迫重选，也不猜测旧实例容量归属；未知未来 schema 禁用选择以避免覆盖。正常改变只 setDirty。

## 组件和预算

SelectionService 保留 UUID 生命周期；SpawnSelectorManager 共享发现任务；StructureTargetResolver 管解析/过滤/viability；MultiStructureLocator 与 StructureInstanceLocator 分离多实例发现和选中 start 读取；StructureCandidatePool/ClaimLedger 管容量；SpawnSafetyValidator 管碰撞政策；TeleportLifecycle 管确认阶段 ticket。

RandomSpread 枚举 potential starts，ConcentricRings 使用 ring positions，都核实真实 valid start。未知 placement 警告后保留 vanilla 单实例 fallback。发现限制：8192 potential planning、512 actual start probes、1200 ticks；manager 每 tick 推进最多四个共享任务。GUI 不提前生成候选的 FULL 地形。

安全搜索先稀疏采样；完全失败后在最多八个有希望区段追加最多 256 点。全程最多 32 个 FULL 请求区块、600 ticks，并有每 tick 检查时间/数量限制。保留真实 bounding box、noCollision、fluid、border、3×3/宽体型支撑、unsafe/leaves、高差、目标 XZ footprint 和其他结构 3D 排除。宽 >8 或高 >16 明确失败。

## 回归命令

build 自动运行 10000 随机外围几何、容量 1/2/-1 与最后槽位序列、真实 NBT 迁移/恢复、Origins 降级隔离、开发/生产 linkage、jar/refmap/default JSON 验证。测试 mod 与参考源码不进入发布包。

localizationTest 不启动游戏，使用真实 FriendlyByteBuf/Component 编解码验证同一服务端消息在 en_us/zh_cn 中解析、嵌套参数和任意原生文本样式。configFilesTest 在项目 build 目录内执行真实文件迁移、改名、外部文案与 GUI 参数同时修改、重复 ID／未来 schema 保护及结构标签路径解析。日常修改优先 build/针对性检查；世界、传送、渲染等变化才按需安排游戏内回归。

```powershell
# 新建测试须使用不存在的 world 名；restart 使用已存在的结构测试世界。
.\gradlew.bat runClient -PclientRegression=structure -PtestWorld=selector-structure-test
.\gradlew.bat runClient -PclientRegression=vanilla -PtestWorld=selector-vanilla-test
.\gradlew.bat runClient -PclientRegression=restart -PtestWorld=selector-structure-test
.\gradlew.bat runClient -PclientRegression=vanillaRestart -PtestWorld=selector-vanilla-test
.\gradlew.bat runClient -PclientRegression=fixtures -PtestWorld=selector-world-tests
.\gradlew.bat runGameTestServer
```

客户端工具使用真实按钮、网络与 renderer ACK，输出 CLIENT_REGRESSION PASS/FAIL，完成后正常保存退出。fixtures 在单人服务器执行八项真实世界 GameTest；独立 GameTest 位于 run/gametest，也要求 EULA。

崩溃恢复 fixture 只接受本项目下已关闭的客户端测试世界：获取 session.lock 后备份 level.dat 和测试玩家 NBT，将玩家位置回退到 holding，保留已完成 SavedData，再用 restart 验证恢复。不要用于正式存档。

```powershell
.\gradlew.bat holdingRecoveryFixture -PtestWorld=selector-structure-test
.\gradlew.bat runClient -PclientRegression=restart -PtestWorld=selector-structure-test
```

已装 Origins 的项目内测试副本由 ForgeGradle deobf，不把 SRG 生产 jar 直接放入开发映射环境：

```powershell
python tools/prepare_origins_fixture.py ../origins-forge-1.20.1-1.10.0.9-all.jar ../caelus-forge-3.2.0+1.20.1.jar
.\gradlew.bat runClient -PoriginsFixture -PclientRegression=origins -PtestWorld=selector-origins-test
.\gradlew.bat runClient -PoriginsFixture -PclientRegression=originsRestart -PtestWorld=selector-origins-test
.\gradlew.bat runClient -PoriginsFixture -PclientRegression=originsBroken -PtestWorld=selector-origins-broken-test
```

审计见 [AUDIT.md](docs/history/AUDIT.md)，Redirect 替换与验证见 [VALIDATION-2.1.5.md](docs/history/VALIDATION-2.1.5.md)，名称编辑修改和验证见 [VALIDATION-2.1.4.md](docs/history/VALIDATION-2.1.4.md)，图标与补全检查见 [VALIDATION-2.1.3.md](docs/history/VALIDATION-2.1.3.md)，分文件配置检查见 [VALIDATION-2.1.2.md](docs/history/VALIDATION-2.1.2.md)，前一轮检查见 [VALIDATION-2.1.1.md](docs/history/VALIDATION-2.1.1.md)，完整世界回归见 [VALIDATION-2.1.0.md](docs/history/VALIDATION-2.1.0.md)。旧 LEVEL2/VALIDATION 文件仅保留为历史。

## 语言与布局

界面按钮、进度/错误/完成消息、命令反馈及配置界面继续提供中英文。默认村庄名称使用翻译键 `spawnselector.entry.village.name`，中文“村庄”、英文“Village”；2.1.6 起默认描述使用 `spawnselector.entry.village.description`，中文“在村庄外围寻找安全的地表出生点。”、英文“Find a safe surface spawn outside a village.”。具体实例的名称（如“村庄 · 地点 1”／“Village · Location 1”）、坐标和容量提示也按各客户端语言翻译。旧版本的条目翻译键保留以兼容已有配置，不强制重写旧文案。已有配置中的固定文字仍优先；需要描述自动翻译时，自行将 `description` 改为 `{"translate":"spawnselector.entry.village.description"}`，也可附加颜色等样式。

名称、描述使用 Minecraft 原生 JSON 文本组件：字符串或 `{"text":"..."}` 是自定义固定文字；`{"translate":"yourpack.spawn.name"}` 引用翻译键；`extra` 或组件数组可混用不同样式和翻译段落。支持 `color`（颜色名／`#RRGGBB`）、`bold`、`italic`、`underlined`、`strikethrough` 和 `\n` 换行；不规定评级、正文、高亮的顺序或段数。背景色高亮不是原生文本组件样式。

```json
{
  "text": "",
  "extra": [
    {
      "text": "推荐开局\n",
      "color": "green",
      "bold": true
    },
    {
      "text": "资源丰富。\n",
      "color": "white"
    },
    {
      "text": "注意危险敌人。",
      "color": "#FFAA00",
      "italic": true
    }
  ]
}
```

将以上组件放在选项文件的 `description` 字段中即可。长描述在出生选择界面内可用滚轮阅读，样式和手动换行保留；提示／页脚位于实际最上方按钮之上，窄窗口的底部按钮自动分行。翻页时同步选中当前页第一项，避免显示另一页却确认旧选项。

自定义 i18n 使用标准客户端资源包，无需特殊 config 语言加载器或 GUI 语言文件入口。创建普通文件夹资源包并启用：

```text
resourcepacks/my-spawn-translations/
├── pack.mcmeta
└── assets/spawnselector/lang/
    ├── zh_cn.json
    └── en_us.json
```

Minecraft 1.20.1 的 `pack.mcmeta`：

```json
{
  "pack": {
    "pack_format": 15,
    "description": "Spawn Selector translations"
  }
}
```

`zh_cn.json`：

```json
{
  "yourpack.spawn.name": "我的要塞",
  "yourpack.spawn.description": "在要塞外围开始冒险。"
}
```

`en_us.json`：

```json
{
  "yourpack.spawn.name": "My Fort",
  "yourpack.spawn.description": "Begin your adventure outside the fort."
}
```

在选项 JSON 中设置 `"name": {"translate": "yourpack.spawn.name"}`、`"description": {"translate": "yourpack.spawn.description"}`。仅想覆盖内置村庄名称也可直接覆盖其现有键。语言资源每种语言一个 JSON，不再按选项拆分。启用资源包后用 F3+T 重新加载资源；修改选项配置另外需要服务端 `/reload`。服务端发送翻译键和参数，各客户端按自己的语言解析；整合包可分发同一资源包，服务器也可提供服务器资源包。修改语言文件不会改变结构、候选池或容量。

## 授权和来源

当前项目采用标准 [GNU AGPLv3](LICENSE)，标识 `AGPL-3.0-only`，从 2.1.1 起生效。旧 MIT 来源声明保留在 LICENSES/MIT-legacy.txt，之前 MIT 版本的授权不撤销。jar 包含 LICENSE、NOTICE 和旧来源声明。

分发修改版时须遵守 AGPL 的源码提供要求；若修改此程序并通过网络让用户与修改版交互，也须按 AGPL 第 13 条向这些用户提供对应源码获取机会。发布源码时包含构建文件，勿把 Minecraft/Forge、缓存、测试世界或 Origins 测试副本当作本项目源码发布。

BetterVillageSpawnPoint 1.20.1 的 CC0-1.0 已核实，仅参考结构过滤和 biome source viability 思路，没有采用村庄内部安全出生算法。来源见 [NOTICE.md](NOTICE.md)。Gradle Wrapper 保留 Apache-2.0 许可。
