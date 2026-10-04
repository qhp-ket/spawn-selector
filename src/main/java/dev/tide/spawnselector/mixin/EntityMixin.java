package dev.tide.spawnselector.mixin;

import dev.tide.spawnselector.SpawnSelectorDimensions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the shared holding platform stable without changing global collision rules. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    private static boolean bothHoldingPlayers(Entity first,Entity second) {
        return first instanceof ServerPlayer a && second instanceof ServerPlayer b
            && SpawnSelectorDimensions.isHolding(a.serverLevel())
            && SpawnSelectorDimensions.isHolding(b.serverLevel());
    }
    @Inject(method="canCollideWith(Lnet/minecraft/world/entity/Entity;)Z",at=@At("HEAD"),cancellable=true)
    private void noHoldingCollision(Entity other,CallbackInfoReturnable<Boolean> cir) {
        if(bothHoldingPlayers((Entity)(Object)this,other)) cir.setReturnValue(false);
    }
    @Inject(method="push(Lnet/minecraft/world/entity/Entity;)V",at=@At("HEAD"),cancellable=true)
    private void noHoldingPush(Entity other,CallbackInfo ci) {
        if(bothHoldingPlayers((Entity)(Object)this,other)) ci.cancel();
    }
}
