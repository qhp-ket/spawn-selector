package dev.tide.spawnselector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Missing data means an existing world, never a newly deferred spawn. */
public final class WorldSpawnState extends SavedData {
    boolean deferred, materialized = true, bonusPending, bonusPlaced;
    transient boolean spawnTicketActive;
    ChunkPos anchor = new ChunkPos(0, 0);
    BlockPos placeholder = BlockPos.ZERO;
    public WorldSpawnState() {}
    private WorldSpawnState(CompoundTag tag) {
        deferred = tag.getBoolean("deferred");
        materialized = tag.getBoolean("materialized");
        anchor = new ChunkPos(tag.getInt("anchor_x"), tag.getInt("anchor_z"));
        placeholder = BlockPos.of(tag.getLong("placeholder_pos"));
        bonusPending = tag.getBoolean("bonus_chest_pending");
        bonusPlaced = tag.getBoolean("bonus_chest_placed");
        if (tag.getInt("schema_version") > 1) {
            deferred = false; materialized = true; bonusPending = false;
            SpawnSelector.LOG.error("Unsupported world-spawn schema; preserving current shared spawn");
        }
    }
    static WorldSpawnState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WorldSpawnState::new, WorldSpawnState::new, "spawnselector_world_spawn");
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema_version", 1);
        tag.putBoolean("deferred", deferred); tag.putBoolean("materialized", materialized);
        tag.putInt("anchor_x", anchor.x); tag.putInt("anchor_z", anchor.z);
        tag.putLong("placeholder_pos", placeholder.asLong());
        tag.putBoolean("bonus_chest_pending", bonusPending); tag.putBoolean("bonus_chest_placed", bonusPlaced);
        return tag;
    }
}
