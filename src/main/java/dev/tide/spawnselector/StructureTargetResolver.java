package dev.tide.spawnselector;

import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Registry resolution and conservative viability; never discovers or generates chunks. */
public final class StructureTargetResolver {
    private StructureTargetResolver() {}
    static HolderSet<Structure> resolve(ServerLevel level, SpawnEntry entry) {
        var raw = Locators.targets(level, entry.target());
        if (raw == null) return HolderSet.direct(List.of());
        return HolderSet.direct(raw.stream().filter(h -> !excluded(h, entry.exclusions()))
            .filter(h -> viable(level, h)).toList());
    }
    static boolean excluded(Holder<Structure> holder, List<String> filters) {
        String id = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
        for (String filter : filters) {
            if (filter.startsWith("#")) {
                if (holder.is(TagKey.create(Registries.STRUCTURE, new ResourceLocation(filter.substring(1))))) return true;
            } else if (filter.contains("*")) {
                String regex = Arrays.stream(filter.split("\\*", -1)).map(Pattern::quote)
                    .collect(java.util.stream.Collectors.joining(".*"));
                if (id.matches(regex)) return true;
            } else if (id.equals(filter)) return true;
        }
        return false;
    }
    static boolean viable(ServerLevel level, Holder<Structure> holder) {
        if (!level.getServer().getWorldData().worldGenOptions().generateStructures()) return false;
        try {
            var generator=level.getChunkSource().getGenerator();
            var source = generator.getBiomeSource();
            if (level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder).isEmpty())
                return !generator.getClass().getName().startsWith("net.minecraft.")
                    || !source.getClass().getName().startsWith("net.minecraft.");
            var possible = source.possibleBiomes();
            var allowed = holder.value().biomes();
            if (possible.isEmpty() || possible.stream().anyMatch(h -> !h.isBound())
                || allowed.stream().anyMatch(h -> !h.isBound())) return true;
            if (allowed.stream().anyMatch(possible::contains)) return true;
            // Custom sources may expose incomplete biome sets or dynamic structures.
            // Only a vanilla source + vanilla structure gives a reliable negative.
            return !source.getClass().getName().startsWith("net.minecraft.")
                || !holder.value().getClass().getName().startsWith("net.minecraft.");
        } catch (RuntimeException | LinkageError ex) {
            SpawnSelector.LOG.debug("Viability is unknown for {}; allowing locate", holder.unwrapKey(), ex);
            return true;
        }
    }
}
