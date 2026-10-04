# 来源说明

本项目以 Spawn Selector 2.0.13 的 MIT 源码为主体。

自 2.1.1 起，当前项目源码与修改按 GNU Affero General Public License v3.0 only（AGPL-3.0-only）提供，完整标准正文见 LICENSE。原 MIT 来源的版权及许可声明保留在 LICENSES/MIT-legacy.txt；该说明不撤销先前 MIT 版本已授予的权利。参考项目和 Gradle Wrapper 保留各自原许可证。

结构生成可行性（placement + biome source）、tag/ID 与 wildcard exclusions 的思路参考：
TurboCrackers/BetterVillageSpawnPoint，1.20.1 分支，
common/src/main/java/com/turbocrackers/bettervillagespawnpoint/util/VillageLocator.java。
https://github.com/TurboCrackers/BetterVillageSpawnPoint/tree/1.20.1

2026-10-03 核实该分支根 LICENSE 为 CC0-1.0，该文件无单独许可证；参考项目快照不随本仓库或产物分发。
仅借鉴并独立实现通用过滤/可行性算法，未移植其内部落点扫描，也未更换 Spawn Selector 生命周期。

Gradle Wrapper 来自 Gradle 8.8（Apache-2.0），脚本保留原版权及许可头；完整文本见 LICENSES/Apache-2.0-Gradle.txt，原 Gradle NOTICE 见 LICENSES/Gradle-NOTICE.txt。

自 2.1.5 起使用 LlamaLad7/MixinExtras 0.5.5（MIT）提供可组合的注入。
https://github.com/LlamaLad7/MixinExtras
依赖以 Forge JarJar 嵌套原包分发，保留其 LICENSE_MixinExtras；不改变该依赖自身许可证。
