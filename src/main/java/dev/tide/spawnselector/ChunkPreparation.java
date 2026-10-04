package dev.tide.spawnselector;

import com.mojang.datafixers.util.Either;
import dev.tide.spawnselector.mixin.ServerChunkCacheAccessor;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.*;

final class ChunkPreparation {
    private ChunkPreparation() {}
    static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> request(
        ServerLevel level, int x, int z, ChunkStatus status) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Chunk requests require server thread");
        // The public 1.20.1 method managedBlock()s on the main thread. Invoke the
        // scheduling-only implementation, then poll the future on subsequent ticks.
        return ((ServerChunkCacheAccessor) level.getChunkSource()).spawnselector$requestChunk(x, z, status, true);
    }
}
