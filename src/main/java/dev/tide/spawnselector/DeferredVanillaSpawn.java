package dev.tide.spawnselector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;

public final class DeferredVanillaSpawn extends PlayerRespawnLogic {
    public record PlayerSpawn(ServerLevel level,BlockPos position) {}
    private DeferredVanillaSpawn() {}
    public static void defer(ServerLevel level, ServerLevelData data, boolean bonus) {
        // Climate.Sampler evaluates density functions only; it has no level/chunk access.
        ChunkPos anchor = new ChunkPos(level.getChunkSource().randomState().sampler().findSpawnPosition());
        BlockPos placeholder = new BlockPos(anchor.getMinBlockX()+8,
            Math.max(level.getMinBuildHeight()+1, Math.min(64, level.getMaxBuildHeight()-2)), anchor.getMinBlockZ()+8);
        WorldSpawnState state = WorldSpawnState.get(level);
        state.deferred=true; state.materialized=false; state.anchor=anchor; state.placeholder=placeholder;
        state.bonusPending=bonus; state.bonusPlaced=false; state.setDirty();
        data.setSpawn(placeholder, 0);
        SpawnSelector.LOG.info("Deferred vanilla spawn at {}; no spawn chunks requested", placeholder);
    }
    public static BlockPos materialize(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Spawn materialization requires server thread");
        ServerLevel level = server.overworld();
        WorldSpawnState state = WorldSpawnState.get(level);
        if (state.deferred && !state.materialized) {
            if (level.getSharedSpawnPos().equals(state.placeholder)) {
                var generator = level.getChunkSource().getGenerator();
                int y = generator.getSpawnHeight(level);
                if (y < level.getMinBuildHeight()) y = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    state.anchor.getMinBlockX()+8, state.anchor.getMinBlockZ()+8);
                BlockPos found = new BlockPos(state.anchor.getMinBlockX()+8, y, state.anchor.getMinBlockZ()+8);
                int x=0,z=0,dx=0,dz=-1;
                for (int i=0;i<121;i++) {
                    if (x>=-5 && x<=5 && z>=-5 && z<=5) {
                        BlockPos candidate=getSpawnPosInChunk(level,new ChunkPos(state.anchor.x+x,state.anchor.z+z));
                        if (candidate!=null) { found=candidate; break; }
                    }
                    if (x==z || (x<0 && x==-z) || (x>0 && x==1-z)) { int old=dx; dx=-dz; dz=old; }
                    x+=dx; z+=dz;
                }
                // Avoid setDefaultSpawnPos: it installs a START region ticket in 1.20.1.
                ((ServerLevelData)level.getLevelData()).setSpawn(found,0);
                server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket(found,0));
            }
            state.materialized=true; state.setDirty();
            SpawnSelector.LOG.info("Vanilla spawn materialized at {}",level.getSharedSpawnPos());
        }
        // Restore the vanilla spawn-region semantics even if optional bonus-chest
        // placement later fails. This ticket is deliberately absent while deferred.
        ensureSpawnTicket(level,state);
        if (state.bonusPending && !state.bonusPlaced) {
            var feature=level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolder(MiscOverworldFeatures.BONUS_CHEST).orElseThrow();
            if (feature.value().place(level,level.getChunkSource().getGenerator(),level.random,level.getSharedSpawnPos())) {
                state.bonusPending=false; state.bonusPlaced=true; state.setDirty();
            }
        }
        return level.getSharedSpawnPos();
    }
    /**
     * Find the player's actual vanilla entry position around the shared spawn.
     * This mirrors ServerPlayer.fudgeSpawnLocation without temporarily moving the
     * real player through every candidate while the holding GUI is still active.
     */
    public static BlockPos findPlayerSpawn(ServerPlayer player) {
        MinecraftServer server=player.server;
        ServerLevel level=server.overworld();
        BlockPos shared=level.getSharedSpawnPos();
        if(level.dimensionType().hasSkyLight() && server.getWorldData().getGameType()!=GameType.ADVENTURE) {
            int radius=Math.max(0,server.getSpawnRadius(level));
            int borderDistance=Mth.floor(level.getWorldBorder().getDistanceToBorder(shared.getX(),shared.getZ()));
            if(borderDistance<radius) radius=borderDistance;
            if(borderDistance<=1) radius=1;
            int diameter=radius*2+1;
            long areaLong=(long)diameter*diameter;
            int area=areaLong>Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)areaLong;
            int step=area<=16 ? area-1 : 17;
            int start=RandomSource.create().nextInt(area);
            for(int i=0;i<area;i++) {
                int index=(start+step*i)%area;
                int dx=index%diameter;
                int dz=index/diameter;
                BlockPos candidate=getOverworldRespawnPos(level,
                    shared.getX()+dx-radius,shared.getZ()+dz-radius);
                if(candidate!=null && collisionFree(player,level,candidate)) return candidate.immutable();
            }
            return null;
        }
        BlockPos candidate=shared;
        while(candidate.getY()<level.getMaxBuildHeight()-1 && !collisionFree(player,level,candidate))
            candidate=candidate.above();
        return collisionFree(player,level,candidate) ? candidate.immutable() : null;
    }
    /** The single entry point for every transition from holding to vanilla. */
    public static PlayerSpawn preparePlayerSpawn(ServerPlayer player) {
        materialize(player.server);
        ServerLevel level=player.server.overworld();
        BlockPos position=findPlayerSpawn(player);
        if(position==null)
            throw new IllegalStateException("Vanilla could not find a collision-free player spawn");
        return new PlayerSpawn(level,position);
    }
    private static boolean collisionFree(ServerPlayer player,ServerLevel level,BlockPos pos) {
        AABB body=player.getBoundingBox().move(pos.getX()+.5-player.getX(),
            pos.getY()-player.getY(),pos.getZ()+.5-player.getZ());
        return level.noCollision(player,body);
    }
    public static void restoreSpawnTicketIfMaterialized(MinecraftServer server) {
        ServerLevel level=server.overworld();
        WorldSpawnState state=WorldSpawnState.get(level);
        if(state.materialized) ensureSpawnTicket(level,state);
    }
    private static void ensureSpawnTicket(ServerLevel level,WorldSpawnState state) {
        if(state.spawnTicketActive) return;
        ChunkPos spawnChunk=new ChunkPos(level.getSharedSpawnPos());
        level.getChunkSource().addRegionTicket(TicketType.START,spawnChunk,11,Unit.INSTANCE);
        state.spawnTicketActive=true;
        SpawnSelector.LOG.info("Vanilla spawn-region START ticket restored at {}",spawnChunk);
    }
}
