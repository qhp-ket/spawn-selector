package dev.tide.spawnselector.mixin;

import java.util.concurrent.CompletableFuture;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> spawnselector$requestChunk(
        int x, int z, ChunkStatus status, boolean create);
}
