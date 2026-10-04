package dev.tide.spawnselector.mixin;

import dev.tide.spawnselector.SpawnSelectorDimensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getSharedSpawnPos()Lnet/minecraft/core/BlockPos;"))
    private static net.minecraft.core.BlockPos constructionPosition(ServerLevel level,Operation<net.minecraft.core.BlockPos> original) {
        return SpawnSelectorDimensions.isHolding(level) ? SpawnSelectorDimensions.HOLDING_POS : original.call(level);
    }
    // DerivedLevelData inherits overworld spawn: never use it for holding construction.
    @Inject(method="fudgeSpawnLocation",at=@At("HEAD"),cancellable=true)
    private void holdingPosition(ServerLevel level,CallbackInfo ci) {
        if(SpawnSelectorDimensions.isHolding(level)) {
            SpawnSelectorDimensions.position((ServerPlayer)(Object)this); ci.cancel();
        }
    }
}
