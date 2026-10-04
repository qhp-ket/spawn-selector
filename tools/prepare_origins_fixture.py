"""Read installed jars and unpack optional test dependencies into this project only.

ForgeGradle handles all SRG-to-development remapping. Nested mods are supplied
individually because FG cannot remap Minecraft calls inside a nested Jar-in-Jar.
This generates local test artifacts only; no repackaged dependencies are shipped.
"""
from pathlib import Path
import io, json, sys, zipfile

root = Path(__file__).resolve().parents[1]
output = root / "test-mods"
output.mkdir(exist_ok=True)
names = []

def unpack(data):
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        if "META-INF/mods.toml" not in source.namelist():
            return False  # Keep ordinary libraries nested; they need no Minecraft remapping.
        metadata = "META-INF/jarjar/metadata.json"
        removed = set()
        retained = []
        if metadata in source.namelist():
            for dependency in json.loads(source.read(metadata))["jars"]:
                if unpack(source.read(dependency["path"])):
                    removed.add(dependency["path"])
                else:
                    retained.append(dependency)
        # Each installed dependency contains one Forge mod. Use its declared ID.
        import re
        mod = re.search(r'modId\s*=\s*"([^"]+)"', source.read("META-INF/mods.toml").decode())[1]
        destination = output / (mod + "-local.jar")
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as jar:
            for item in source.infolist():
                if item.filename in removed or item.filename == metadata or item.filename.endswith((".SF", ".RSA", ".DSA")):
                    continue
                jar.writestr(item, source.read(item.filename))
            if retained:
                jar.writestr(metadata,json.dumps({"jars":retained}))
        names.append(mod)
        return True

for installed in sys.argv[1:]:
    unpack(Path(installed).read_bytes())
(output / "dependencies.json").write_text(json.dumps(sorted(set(names))), encoding="utf-8")
print("Prepared project-local optional test dependencies:", ", ".join(names))
