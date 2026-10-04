# Spawn Selector

[English](README.md) | [简体中文](README.zh-CN.md)

[![Build and verify](https://github.com/qhp-ket/spawn-selector/actions/workflows/build.yml/badge.svg)](https://github.com/qhp-ket/spawn-selector/actions/workflows/build.yml)

Choose a safe first spawn **outside a structure**. Spawn Selector supports multiple instances of each structure, per-instance player limits, custom entries, and optional Origins compatibility.

**Minecraft 1.20.1 · Forge 47.4.23 · Java 17.** Install the same mod version on the server and every client. Origins is optional.

## Features

- New players wait in a holding dimension while choosing their first spawn. Movement and interactions are restricted, with protection until the destination is ready on their client.
- Choose a structure type, then a specific instance. Other players reuse the server's discovered candidates.
- Spawn outside the structure's footprint, facing its center. Safety checks use the player's actual dimensions and collision box.
- Set an instance's capacity to one player, several players, or unlimited sharing. Completed choices persist across restarts.
- Wait for Origins to finish its own selection. A broken compatibility API falls back independently for each player.
- Defer unused vanilla spawn preparation. Choosing to skip selection materializes vanilla spawn instead.

## Installation and building

Install JDK 17 and Python 3.8+, set `JAVA_HOME`, and make Python available on PATH. No existing Minecraft installation or system Gradle is required. The first build downloads dependencies.

Windows:

```powershell
.\gradlew.bat build
```

Linux / macOS:

```sh
./gradlew build
```

Copy `build/libs/spawnselector-2.1.7-forge-1.20.1.jar` into your instance's `mods` directory and keep one active Spawn Selector version. The `-dev.jar` uses development mappings; `-thin.jar` is an intermediate artifact.

Wrapper downloads are checksum-verified. The default cache is `.gradle-home`; `GRADLE_USER_HOME` and `--gradle-user-home` are respected. Python defaults to `python` on Windows and `python3` elsewhere; override it with `-PpythonExecutable=/path/to/python` or `PYTHON`.

Use `runClient` or `runServer` for development. Server runs require the user's EULA agreement. Build and contribution instructions are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Configuration

The only bundled option is **Village**, targeting `#minecraft:village`. Save in the Forge Mods config screen, or use its Open JSON buttons, to create:

```text
config/spawnselector/
├── settings.json
└── entries/
    └── village.json
```

The GUI edits names and parameters, previews icons and descriptions, supports structure IDs and tags, and opens the corresponding JSON files. Edit rich descriptions, colors, and translation keys in JSON. Editing a name in the GUI converts it to custom literal text; leaving it unchanged preserves its translation key.

For manual setup, `settings.json` activates the split configuration format:

```json
{"schema_version": 2, "layout": {}}
```

For example, `entries/village.json` can override the bundled Village option:

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

| Field | Meaning |
| --- | --- |
| `target` | Structure registry ID or `#structure_tag`. |
| `candidate_count` | Maximum distinct instances to discover, 1–32; default 3. Discovery may find fewer. |
| `capacity_per_instance` | 1–10000 players, or `-1` for unlimited sharing; default `-1`. |
| `min_distance`, `max_distance` | Outside-search distances from the structure bounding box; defaults 8 and 48 blocks. |
| `require_open_sky` | Require an open sky above the spawn; default `true`. Other safety checks still apply when disabled. |
| `exclusions` | Structure IDs, wildcard IDs such as `mymod:ruined_*`, or `#tags`. |

External fields override bundled or data-pack definitions. GUI saves materialize complete entry files. Use `enabled: false` to disable an option; deleting a bundled option's file restores its defaults. File renames preserve the `id`; changing the `id` creates a different entry.

In multiplayer, the **server's configuration and data packs** determine options and capacities. A remote client cannot save changes to the server through its local editor. After editing server files, run `/reload` with the required administrative permission or restart the world.

The old `config/spawnselector.json` remains readable until split settings exist. The first GUI save migrates it without overwriting the old file. Existing custom text is preserved.

Data-pack entry definitions live at `data/<namespace>/spawn_entries/<path>.json`, using the file's resource location as their ID. Layout overrides use `data/spawnselector/spawn_ui/layout.json`.

## Multiplayer and saved choices

Claims are checked and committed on the server thread. Cancelling, preparation failure, or disconnecting before arrival releases a temporary claim; interrupted reservations are recovered after a restart. A completed first spawn permanently consumes capacity, including after death or logout. Administrative `/spawnselector reset` does not reclaim completed capacity.

Aliases targeting the same instance share claims and use the strictest finite capacity. Lowering capacity does not evict players. Cached candidates survive restarts; discovery-relevant configuration changes invalidate the matching discovery cache. Each new player still receives safety checks for their own size.

Old completed player records are preserved during SavedData upgrades; players are not forced to select again.

## Text and translations

Names and descriptions use Minecraft JSON text components. Literal text stays untranslated; `translate` references a language key. Arrays and `extra` can combine styles and translated segments:

```json
{
  "description": [
    {"translate": "spawnselector.entry.village.description", "color": "white"},
    {"text": "\nCustom note", "color": "gold", "bold": true, "italic": true}
  ]
}
```

Built-in English and Chinese cover UI messages, the default Village name and description, instance numbering, coordinates, and capacity hints. Each client uses its own language. Configured literal names and descriptions remain literal.

To add or override translations, use a standard client resource pack with `pack_format: 15` and files such as `assets/spawnselector/lang/en_us.json` and `zh_cn.json`. Reference your keys through `{"translate":"yourpack.spawn.description"}`. Reload client resources with F3+T; changing entry configuration separately requires a server reload. Config edits do not edit language files.

## Validation and limitations

Windows and Ubuntu CI build both artifacts, compile the integration tests, and execute the production Mixin smoke checks. Automated checks cover capacities, SavedData, configuration, localization, Origins failure handling, perimeter geometry, linkage, and jar contents. A clean-source build with an initially empty dependency cache has also passed.

Discovery and terrain preparation have bounded budgets. Unrecognized custom placements fall back to vanilla single-instance locating. Ceiling dimensions and players wider than 8 or taller than 16 blocks are unsupported. Full dedicated-server world and real two-client multiplayer regression testing remain outstanding; automated checks do not replace those tests.

## License and credits

[GNU AGPL-3.0-only](LICENSE). Legacy MIT notices and third-party licenses are retained.

Structure filtering and biome viability ideas were independently adapted from BetterVillageSpawnPoint's CC0-1.0 source. Attribution, Gradle Wrapper, and MixinExtras notices are in [NOTICE.md](NOTICE.md).
