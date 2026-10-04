package dev.tide.spawnselector;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;

public final class Locators {
    record Located(BoundingBox bounds, boolean ready) {}
    static ServerLevel level(MinecraftServer server, SpawnEntry e) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, e.dimension()));
    }
    static HolderSet<Structure> targets(ServerLevel level, String target) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        if (target.startsWith("#")) return registry.getTag(TagKey.create(Registries.STRUCTURE,
            new ResourceLocation(target.substring(1)))).orElse(null);
        return registry.getHolder(ResourceKey.create(Registries.STRUCTURE, new ResourceLocation(target)))
            .<HolderSet<Structure>>map(HolderSet::direct).orElse(null);
    }
    static boolean available(MinecraftServer server, SpawnEntry e) {
        try {
            ServerLevel level = level(server, e);
            if (level == null || level.dimensionType().hasCeiling()) return false;
            HolderSet<Structure> targets = StructureTargetResolver.resolve(level, e);
            if (targets == null || targets.size() == 0 || !level.getServer().getWorldData().worldGenOptions().generateStructures()) return false;
            return true; // resolve() already handles placement + conservative biome viability.
        } catch (RuntimeException | LinkageError ex) {
            SpawnSelector.LOG.debug("Structure entry {} is unavailable", e.id(), ex);
            return false;
        }
    }
    /** Called only on the server thread: vanilla locate is not thread-safe. */
    static Located locate(ServerLevel level, SpawnEntry e, BlockPos near) {
        HolderSet<Structure> targets = StructureTargetResolver.resolve(level, e);
        if (targets == null || targets.size() == 0) return null;
        var result = level.getChunkSource().getGenerator().findNearestMapStructure(level, targets, near, e.searchRadius(), false);
        if (result == null) return null;
        BlockPos p = result.getFirst();
        if (!level.getWorldBorder().isWithinBounds(p)) return null;
        // locate normally returns the start chunk. Read its start directly; do not test the
        // arbitrary Y returned by locate against getStructureAt's 3D bounding box.
        var chunk = level.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS);
        StructureStart start = chunk.getStartForStructure(result.getSecond().value());
        if (start == null || !start.isValid()) {
            level.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES);
            start = level.structureManager().startsForStructure(new net.minecraft.world.level.ChunkPos(p),
                s -> s == result.getSecond().value()).stream().filter(StructureStart::isValid)
                .min(java.util.Comparator.comparingDouble(s -> s.getBoundingBox().getCenter().distSqr(p))).orElse(null);
        }
        return start != null && start.isValid() ? new Located(start.getBoundingBox(), true) : null;
    }
}
