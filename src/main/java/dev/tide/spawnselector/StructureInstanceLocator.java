package dev.tide.spawnselector;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;

/** Reads one chosen start's bounds; discovery records contain only stable identity. */
final class StructureInstanceLocator implements AutoCloseable {
    private static final TicketType<UUID> TICKET = TicketType.create("spawnselector_start", UUID::compareTo);
    private static final int RADIUS = 33 - ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);
    private final ServerLevel level;
    private final StructureInstanceId id;
    private final UUID owner = UUID.randomUUID();
    private final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
    private final long deadline;
    private boolean closed;
    StructureInstanceLocator(ServerLevel level, StructureInstanceId id) {
        this.level = level; this.id = id; deadline = level.getGameTime() + 600;
        var chunk = new ChunkPos(id.chunkX(), id.chunkZ());
        level.getChunkSource().addRegionTicket(TICKET, chunk, RADIUS, owner);
        try { future = ChunkPreparation.request(level, id.chunkX(), id.chunkZ(), ChunkStatus.STRUCTURE_STARTS); }
        catch (RuntimeException | LinkageError ex) { close(); throw ex; }
    }
    boolean done() { return future.isDone() || level.getGameTime() >= deadline; }
    BoundingBox bounds() {
        if (!future.isDone() || future.isCompletedExceptionally()) return null;
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(new ResourceLocation(id.structureId()));
        if (structure == null) return null;
        var chunk = future.join().left().orElse(null);
        if (chunk == null) return null;
        var start = chunk.getStartForStructure(structure);
        return start != null && start.isValid() ? start.getBoundingBox() : null;
    }
    static StructureInstanceId identity(ServerLevel level, Holder<Structure> holder, StructureStart start) {
        return new StructureInstanceId(level.dimension().location().toString(), holder.unwrapKey().orElseThrow().location().toString(),
            start.getChunkPos().x, start.getChunkPos().z);
    }
    static StructureInstanceId nearest(ServerLevel level, HolderSet<Structure> targets, BlockPos near, int radius) {
        var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, targets, near, radius, false);
        if (located == null) return null;
        var pos = new ChunkPos(located.getFirst());
        var chunk = level.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS);
        var start = chunk.getStartForStructure(located.getSecond().value());
        if (start == null || !start.isValid()) {
            level.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_REFERENCES);
            start = level.structureManager().startsForStructure(pos, s -> s == located.getSecond().value()).stream()
                .filter(StructureStart::isValid).min(Comparator.comparingDouble(s -> s.getBoundingBox().getCenter().distSqr(near))).orElse(null);
        }
        return start == null || !start.isValid() ? null : identity(level, located.getSecond(), start);
    }
    @Override public void close() {
        if (!closed) level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(id.chunkX(), id.chunkZ()), RADIUS, owner);
        closed = true;
    }
}
