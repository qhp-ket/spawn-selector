package dev.tide.spawnselector.mixin;

import dev.tide.spawnselector.*;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    // Skip the initial terrain search, not just startup's START ticket.
    @Inject(method="setInitialSpawn",at=@At("HEAD"),cancellable=true)
    private static void defer(ServerLevel level,ServerLevelData data,boolean bonus,boolean debug,CallbackInfo ci) {
        if(!debug) { DeferredVanillaSpawn.defer(level,data,bonus); ci.cancel(); }
    }
    @WrapWithCondition(method="prepareLevels",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"))
    private <T> boolean skipStart(ServerChunkCache cache,TicketType<T> type,ChunkPos pos,int radius,T value) {
        if(((MinecraftServer)(Object)this).getLevel(SpawnSelectorDimensions.HOLDING)!=null && type==TicketType.START) {
            SpawnSelector.LOG.info("Vanilla START spawn-region preparation skipped");
            return false;
        }
        return true;
    }
    // Modify the loop predicate, not the counter or its target. A wildcard RHS
    // also preserves another mod's spawn-region size when normal preparation runs.
    @Definition(id="tickingGenerated",method="Lnet/minecraft/server/level/ServerChunkCache;getTickingGenerated()I")
    @Expression("?.tickingGenerated() != ?")
    @ModifyExpressionValue(method="prepareLevels",at=@At("MIXINEXTRAS:EXPRESSION"))
    private boolean skipWait(boolean original) {
        return ((MinecraftServer)(Object)this).getLevel(SpawnSelectorDimensions.HOLDING)==null && original;
    }
}
