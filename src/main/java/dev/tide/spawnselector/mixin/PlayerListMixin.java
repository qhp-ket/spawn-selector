package dev.tide.spawnselector.mixin;

import dev.tide.spawnselector.*;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import com.mojang.authlib.GameProfile;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @ModifyArg(method="getPlayerForLogin",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V"),index=1)
    private ServerLevel construction(MinecraftServer server,ServerLevel original,GameProfile profile) {
        return SpawnSelectorDimensions.constructionLevel(server,original,profile.getId());
    }
    // After NBT load, BEFORE login packet and addNewPlayer. The adjusted result changes
    // vanilla's local level, so its packets, game mode and entity insertion agree.
    @ModifyExpressionValue(method="placeNewPlayer",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel placement(ServerLevel original,Connection connection,ServerPlayer player) {
        return SpawnSelectorDimensions.route(player,original);
    }
    // Pending players must not probe old beds or reach overworld on death.
    @ModifyExpressionValue(method="respawn",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;getRespawnPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos respawnPos(BlockPos original,ServerPlayer player,boolean keep) {
        return pending(player) ? null : original;
    }
    @WrapOperation(method="respawn",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel respawnFallback(MinecraftServer server,Operation<ServerLevel> original,ServerPlayer old,boolean keep) {
        if(pending(old)) {
            ServerLevel holding=server.getLevel(SpawnSelectorDimensions.HOLDING);
            SpawnSelectorDimensions.ensurePlatform(holding); return holding;
        }
        DeferredVanillaSpawn.materialize(server);
        return original.call(server);
    }
    private static boolean pending(ServerPlayer player) {
        return player.server.getLevel(SpawnSelectorDimensions.HOLDING)!=null && SelectionEligibility.shouldOffer(player);
    }
}
