package dev.tide.spawnselector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;

public final class SpawnSelectorDimensions {
    public static final ResourceKey<Level> HOLDING=ResourceKey.create(Registries.DIMENSION,new ResourceLocation("spawnselector:holding"));
    // Center the 5x5 platform inside ONE chunk (0,0 would straddle four chunks).
    public static final BlockPos HOLDING_POS=new BlockPos(8,64,8);
    public static final Vec3 HOLDING_VEC=new Vec3(8.5,64.0,8.5);
    private static final Map<ServerLevel,Long> platformCheckedAt=new WeakHashMap<>();
    public static boolean isHolding(ServerLevel level) { return level.dimension().equals(HOLDING); }
    public static ServerLevel constructionLevel(MinecraftServer server,ServerLevel fallback,UUID playerId) {
        // Construction precedes player NBT loading. Even returning structure players
        // must not run fudgeSpawnLocation in the unused vanilla spawn region here.
        ServerLevel holding=server.getLevel(HOLDING);
        return holding==null ? fallback : holding;
    }
    public static void position(ServerPlayer player) {
        player.moveTo(HOLDING_VEC.x,HOLDING_VEC.y,HOLDING_VEC.z,0,0);
        player.setDeltaMovement(Vec3.ZERO); player.fallDistance=0;
    }
    /** Verify at most once per second per holding level; rebuild only after damage. */
    public static void ensurePlatform(ServerLevel level) {
        long now=level.getGameTime();
        Long last=platformCheckedAt.get(level);
        if(last!=null && now-last<20) return;
        boolean intact=true;
        outer: for(int x=6;x<=10;x++) for(int z=6;z<=10;z++) {
            if(!level.getBlockState(new BlockPos(x,63,z)).is(Blocks.BARRIER)) { intact=false; break outer; }
            for(int y=64;y<=68;y++) if(!level.getBlockState(new BlockPos(x,y,z)).isAir()) { intact=false; break outer; }
        }
        if(!intact) rebuildPlatform(level);
        platformCheckedAt.put(level,now);
    }
    private static void rebuildPlatform(ServerLevel level) {
        for(int x=6;x<=10;x++) for(int z=6;z<=10;z++) {
            level.setBlock(new BlockPos(x,63,z),Blocks.BARRIER.defaultBlockState(),2);
            for(int y=64;y<=68;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
    }
    public static ServerLevel route(ServerPlayer player,ServerLevel saved) {
        ServerLevel holding=player.server.getLevel(HOLDING);
        var selections=PlayerSelections.get(player.server);
        if (saved != null && isHolding(saved) && selections.chosen(player.getUUID())) {
            // Player NBT and SavedData are separate save files. A crash can persist
            // COMPLETE before the player's last holding NBT has been replaced.
            var record = selections.selection(player.getUUID());
            ResourceLocation dimension=ResourceLocation.tryParse(record.getString("dimension"));
            ServerLevel target=dimension==null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION,dimension));
            if (target != null && !isHolding(target) && record.contains("spawn_pos")
                && !record.getString("entry").equals("spawnselector:vanilla")) {
                BlockPos pos = BlockPos.of(record.getLong("spawn_pos"));
                player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
                player.setRespawnPosition(target.dimension(),pos,0,true,false);
                selections.resumeArrival(player.getUUID());
                SpawnSelector.LOG.info("Recovered completed structure selection for {} from stale holding player NBT",player.getUUID());
                return target;
            }
        }
        if(holding!=null && SelectionEligibility.shouldOffer(player)) {
            PlayerSelections.get(player.server).pending(player.getUUID(),true);
            ensurePlatform(holding); position(player);
            SpawnSelector.LOG.info("First placement of {} routed to holding",player.getUUID());
            return holding;
        }
        // A disabled/incompatible selection record must not strand a saved holding player.
        if(saved!=null && isHolding(saved)) {
            var spawn=DeferredVanillaSpawn.preparePlayerSpawn(player);
            BlockPos pos=spawn.position();
            player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
            return spawn.level();
        }
        return saved;
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        if(event.getServer().getLevel(HOLDING)==null) {
            SpawnSelector.LOG.error("SpawnSelector holding dimension is unavailable; falling back to legacy spawn-selection behavior.");
            try { DeferredVanillaSpawn.materialize(event.getServer()); }
            catch(RuntimeException | LinkageError ex) {
                SpawnSelector.LOG.error("Could not materialize fallback vanilla spawn",ex);
            }
        } else {
            ensurePlatform(event.getServer().getLevel(HOLDING));
            DeferredVanillaSpawn.restoreSpawnTicketIfMaterialized(event.getServer());
        }
    }
}
