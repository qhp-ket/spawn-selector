package dev.tide.spawnselector;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

/** Client-side structure candidates used only by the Mods configuration editor. */
final class StructureCatalog {
    private static volatile List<Entry> installedCache;
    record Entry(ResourceLocation id, String namespace, String sourceName, String requiredMod, boolean tag, int members) {
        String target() { return (tag ? "#" : "") + id; }
        String label() {
            String path = id.getPath().replace('/', ' ').replace('_', ' ');
            return path.isBlank() ? id.toString() : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        }
    }

    private StructureCatalog() {}

    static List<Entry> load() {
        Minecraft minecraft = Minecraft.getInstance();
        Map<String, String> names = modNames();
        Map<String, Entry> found = new HashMap<>();
        if (minecraft.level != null) {
            try {
                Registry<Structure> registry = minecraft.level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                for (ResourceLocation id : registry.keySet()) add(found, id, names);
                registry.getTags().forEach(pair -> add(found, pair.getFirst().location(), names, true, pair.getSecond().size()));
            } catch (RuntimeException ex) {
                SpawnSelector.LOG.warn("Unable to read the current structure registry", ex);
            }
        }
        if (found.isEmpty()) return installedStructures(names);
        addCommonVanilla(found, names);
        addVanillaTags(found,names);
        return sorted(found);
    }

    private static List<Entry> installedStructures(Map<String, String> names) {
        List<Entry> cached = installedCache;
        if (cached != null) return cached;
        synchronized (StructureCatalog.class) {
            if (installedCache == null) {
                Map<String, Entry> found = new HashMap<>();
                scanInstalledMods(found, names);
                addCommonVanilla(found, names);
                addVanillaTags(found,names);
                installedCache = sorted(found);
            }
            return installedCache;
        }
    }

    private static List<Entry> sorted(Map<String, Entry> found) {
        return found.values().stream().sorted(Comparator.comparing((Entry e) -> e.namespace)
            .thenComparing(Entry::target)).toList();
    }

    private static Map<String, String> modNames() {
        Map<String, String> result = new HashMap<>();
        try {
            for (IModInfo info : ModList.get().getMods())
                result.putIfAbsent(info.getModId(), info.getDisplayName());
        } catch (RuntimeException ex) {
            SpawnSelector.LOG.warn("Unable to read loaded mod names for structure picker", ex);
        }
        result.putIfAbsent("minecraft", "Minecraft");
        return result;
    }

    private static void add(Map<String, Entry> found, ResourceLocation id, Map<String, String> names) {
        add(found,id,names,false,-1);
    }

    private static void add(Map<String, Entry> found, ResourceLocation id, Map<String, String> names, boolean tag, int members) {
        if (id == null || id.getNamespace().isBlank() || id.getPath().isBlank()) return;
        String namespace = id.getNamespace();
        String source = names.getOrDefault(namespace, namespace);
        // A namespace from a world datapack is not necessarily a Forge mod id.
        // Only auto-fill required_mod when that namespace is actually loaded as a mod.
        String required = tag || namespace.equals("minecraft") || !ModList.get().isLoaded(namespace) ? "" : namespace;
        String target = (tag ? "#" : "") + id;
        found.putIfAbsent(target, new Entry(id, namespace, source, required, tag, members));
    }

    private static void scanInstalledMods(Map<String, Entry> found, Map<String, String> names) {
        Set<Path> files = new HashSet<>();
        try {
            for (IModFileInfo info : ModList.get().getModFiles()) {
                Path file = info.getFile().getFilePath();
                if (file != null) files.add(file);
            }
        } catch (RuntimeException ex) {
            SpawnSelector.LOG.warn("Unable to enumerate installed mod files for structure picker", ex);
        }
        for (Path file : files) {
            try {
                if (Files.isDirectory(file)) scanDirectory(file, found, names);
                else scanArchive(file, found, names);
            } catch (IOException | RuntimeException ex) {
                SpawnSelector.LOG.debug("Unable to scan {} for structures", file, ex);
            }
        }
    }

    private static void scanDirectory(Path root, Map<String, Entry> found, Map<String, String> names) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String resource = root.relativize(path).toString().replace('\\', '/');
                addResource(found,resource,names);
            });
        }
    }

    private static void scanArchive(Path file, Map<String, Entry> found, Map<String, String> names) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                addResource(found,entries.nextElement().getName(),names);
            }
        }
    }

    private static void addResource(Map<String,Entry> found,String path,Map<String,String> names) {
        ResourceLocation id=idFromPath(path,false);if(id!=null)add(found,id,names);
        id=idFromPath(path,true);if(id!=null)add(found,id,names,true,-1);
    }

    static ResourceLocation idFromPath(String path,boolean tag) {
        String prefix = "data/";
        String marker = tag ? "/tags/worldgen/structure/" : "/worldgen/structure/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) return null;
        int namespaceEnd = path.indexOf('/', prefix.length());
        if (namespaceEnd < 0) return null;
        String namespace = path.substring(prefix.length(), namespaceEnd);
        String body = path.substring(namespaceEnd);
        if (!body.startsWith(marker) || body.length() <= marker.length() + 5) return null;
        String structurePath = body.substring(marker.length(), body.length() - 5);
        return ResourceLocation.tryParse(namespace + ":" + structurePath);
    }

    private static void addCommonVanilla(Map<String, Entry> found, Map<String, String> names) {
        String[] ids = {"village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga",
            "pillager_outpost", "shipwreck", "stronghold", "mineshaft", "desert_pyramid", "jungle_pyramid",
            "ocean_monument", "woodland_mansion", "nether_fossil", "bastion_remnant", "ruined_portal",
            "end_city", "ancient_city", "trail_ruins"};
        for (String id : ids) add(found, new ResourceLocation("minecraft", id), names);
    }
    private static void addVanillaTags(Map<String,Entry> found,Map<String,String> names) {
        for(var tag:List.of(StructureTags.VILLAGE,StructureTags.MINESHAFT,StructureTags.SHIPWRECK,
            StructureTags.RUINED_PORTAL,StructureTags.OCEAN_RUIN,StructureTags.EYE_OF_ENDER_LOCATED,
            StructureTags.DOLPHIN_LOCATED,StructureTags.ON_WOODLAND_EXPLORER_MAPS,
            StructureTags.ON_OCEAN_EXPLORER_MAPS,StructureTags.ON_TREASURE_MAPS,
            StructureTags.CATS_SPAWN_IN,StructureTags.CATS_SPAWN_AS_BLACK))add(found,tag.location(),names,true,-1);
    }
}
