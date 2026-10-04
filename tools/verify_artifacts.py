"""Validate Gradle's development and production artifacts without touching the instance."""
import json, pathlib, sys, zipfile, re, io

project = pathlib.Path(__file__).resolve().parents[1]
version = re.search(r'^mod_version=(.+)$', (project / 'gradle.properties').read_text(encoding='utf-8'), re.M)[1]

for archive in map(pathlib.Path, sys.argv[1:]):
    with zipfile.ZipFile(archive) as jar:
        names = jar.namelist()
        assert not any(n.startswith(('net/minecraft/', 'net/minecraftforge/', 'com/turbocrackers/')) for n in names)
        assert not any(n.endswith('.java') or n.startswith('dev/tide/spawnselectortests/') for n in names)
        for name in names:
            if name.endswith(('.json', '.mcmeta')):
                json.loads(jar.read(name))
            if name.endswith('.class'):
                assert int.from_bytes(jar.read(name)[6:8], 'big') == 61, name
        defaults = json.loads(jar.read('default_spawnselector.json'))
        assert set(defaults['entries']) == {'spawnselector:village'}
        assert {n for n in names if '/spawn_entries/' in n and n.endswith('.json')} == {'data/spawnselector/spawn_entries/village.json'}
        assert set(json.loads(jar.read('legacy_spawn_entries.json'))) == {'spawnselector:bobbar', 'spawnselector:cloister', 'spawnselector:chest_museum'}
        for name in names:
            if '/spawn_entries/' in name and name.endswith('.json'):
                assert defaults['entries']['spawnselector:' + pathlib.PurePosixPath(name).stem] == json.loads(jar.read(name))
        assert defaults['layout'] == json.loads(jar.read('data/spawnselector/spawn_ui/layout.json'))
        nested = json.loads(jar.read('META-INF/jarjar/metadata.json'))['jars']
        assert len(nested) == 1 and nested[0]['identifier']['artifact'] == 'mixinextras-forge'
        assert nested[0]['version'] == {'range': '[0.5.5,)', 'artifactVersion': '0.5.5'}
        with zipfile.ZipFile(io.BytesIO(jar.read(nested[0]['path']))) as library:
            assert b'MIT License' in library.read('LICENSE_MixinExtras')
            core = json.loads(library.read('META-INF/jarjar/metadata.json'))['jars']
            assert len(core) == 1 and core[0]['version']['artifactVersion'] == '0.5.5'
            with zipfile.ZipFile(io.BytesIO(library.read(core[0]['path']))) as common:
                assert 'com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.class' in common.namelist()
        assert not any(n.startswith('com/llamalad7/') for n in names)
        assert b'net/minecraft/client/gui/screens/Screen' not in jar.read('dev/tide/spawnselector/SpawnSelector.class')
        assert json.loads(jar.read('spawnselector.mixins.json'))['mixinextras']['minVersion'] == '0.5.5'
        for name in names:
            if name.startswith('dev/tide/spawnselector/mixin/') and name.endswith('.class'):
                assert b'Lorg/spongepowered/asm/mixin/injection/Redirect;' not in jar.read(name), name
        refmap = json.loads(jar.read('spawnselector.refmap.json'))
        for mixin, hooks in {
            'MinecraftServerMixin': ['setInitialSpawn', 'prepareLevels'],
            'PlayerListMixin': ['getPlayerForLogin', 'placeNewPlayer', 'respawn'],
            'ServerPlayerMixin': ['fudgeSpawnLocation'],
            'EntityMixin': ['canCollideWith', 'push'],
            'ServerChunkCacheAccessor': ['getChunkFutureMainThread'],
        }.items():
            mappings = refmap['mappings']['dev/tide/spawnselector/mixin/' + mixin]
            for hook in hooks:
                assert any(hook in key and 'm_' in value for key, value in mappings.items()), (mixin, hook)
        assert b'MixinConfigs: spawnselector.mixins.json' in jar.read('META-INF/MANIFEST.MF')
        assert f'version="{version}"'.encode() in jar.read('META-INF/mods.toml')
        assert b'license="AGPL-3.0-only"' in jar.read('META-INF/mods.toml')
        assert b'GNU AFFERO GENERAL PUBLIC LICENSE' in jar.read('META-INF/LICENSE')
        assert b'MIT License' in jar.read('META-INF/LICENSES/MIT-legacy.txt')
        assert b'Apache License' in jar.read('META-INF/LICENSES/Apache-2.0-Gradle.txt')
        assert jar.read('META-INF/LICENSES/Gradle-NOTICE.txt')
        assert jar.read('META-INF/NOTICE.md')
        zh = json.loads(jar.read('assets/spawnselector/lang/zh_cn.json'))
        en = json.loads(jar.read('assets/spawnselector/lang/en_us.json'))
        assert zh.keys() == en.keys()
        for key in zh:
            assert re.findall(r'%(?:\d+\$)?[sd]', zh[key]) == re.findall(r'%(?:\d+\$)?[sd]', en[key]), key
        for path in project.joinpath('src/main/java').rglob('*.java'):
            for key in re.findall(r'Component\.translatable\("(spawnselector\.[^"\n]+)"', path.read_text(encoding='utf-8')):
                if not key.endswith('.'):
                    assert key in en, (path.name, key)
    print('PASS: Java17, generated SRG refmap, resources/defaults, nested MixinExtras, no Redirect or shaded game classes:', archive.name)
