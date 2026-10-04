package dev.tide.spawnselector;

import java.util.UUID;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/** World-owned UUID records survive death, reconnect, and dimension changes without clone hooks. */
public final class PlayerSelections extends SavedData {
    private static final int CURRENT_SCHEMA = 2;
    private static final Set<MinecraftServer> UNSUPPORTED_SERVERS = Collections.newSetFromMap(new WeakHashMap<>());
    private final CompoundTag players;
    private final boolean unsupported;
    final StructureCandidatePool pool = new StructureCandidatePool(this);
    private PlayerSelections() { players = new CompoundTag(); unsupported = false; }
    private PlayerSelections(boolean unsupported) { players = new CompoundTag(); this.unsupported = unsupported; }
    private PlayerSelections(CompoundTag tag) {
        int version = tag.contains("schema_version", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)
            ? tag.getInt("schema_version") : 0;
        if (version > CURRENT_SCHEMA)
            throw new UnsupportedOperationException("Unsupported SpawnSelector player data schema " + version + " (current " + CURRENT_SCHEMA + ")");
        players = tag.getCompound("players").copy();
        unsupported = false;
        for (String id : players.getAllKeys()) {
            CompoundTag record = players.getCompound(id);
            if (record.contains("entry") && !record.contains("state")) {
                record.putString("entry_id", record.getString("entry"));
                record.putLong("spawn_pos", record.getLong("position"));
                record.putString("state", "LEGACY_COMPLETE");
            } else if (!record.contains("entry")) {
                record.remove("instance"); record.putString("state", "PENDING");
            }
        }
        pool.load(tag.getCompound("candidate_pool"));
        setDirty();
    }
    static PlayerSelections get(MinecraftServer server) {
        if (UNSUPPORTED_SERVERS.contains(server)) return new PlayerSelections(true);
        try {
            return server.overworld().getDataStorage().computeIfAbsent(PlayerSelections::new,
                PlayerSelections::new, "spawnselector_players");
        } catch (UnsupportedOperationException ex) {
            UNSUPPORTED_SERVERS.add(server);
            SpawnSelector.LOG.error("SpawnSelector is disabled for this server: {}", ex.getMessage());
            return new PlayerSelections(true);
        }
    }
    public boolean chosen(UUID id) { return unsupported || players.getCompound(id.toString()).contains("entry"); }
    void reset(UUID id) { if (!unsupported) { players.remove(id.toString()); setDirty(); } }
    boolean pending(UUID id) { return !unsupported && players.getCompound(id.toString()).getBoolean("pending"); }
    void pending(UUID id, boolean value) {
        if (unsupported || chosen(id)) return;
        CompoundTag tag = players.getCompound(id.toString()).copy(); tag.putBoolean("pending", value);
        players.put(id.toString(), tag); setDirty();
    }
    void complete(ServerPlayer player, String entry) {
        if (unsupported) return;
        CompoundTag tag = new CompoundTag();
        tag.putString("entry", entry);
        tag.putString("entry_id", entry);
        tag.putString("dimension", player.serverLevel().dimension().location().toString());
        tag.putLong("position", player.blockPosition().asLong());
        tag.putLong("spawn_pos", player.blockPosition().asLong());
        tag.putString("state", "ARRIVED");
        CompoundTag previous = players.getCompound(player.getUUID().toString());
        if (previous.contains("instance")) {
            CompoundTag instance = previous.getCompound("instance").copy();
            tag.put("instance", instance);
            tag.putString("structure_id", instance.getString("structure_id"));
            tag.putLong("structure_start_chunk", instance.getLong("structure_start_chunk"));
            pool.complete(player.server, StructureCandidatePool.readIdentity(instance), player.getUUID());
        }
        players.put(player.getUUID().toString(), tag); setDirty();
    }
    void reserve(UUID player, String entry, StructureInstanceId id) {
        CompoundTag tag = players.getCompound(player.toString()).copy();
        tag.putString("entry_id", entry); tag.put("instance", StructureCandidatePool.identity(id));
        tag.putString("structure_id",id.structureId()); tag.putString("dimension",id.dimension());
        tag.putLong("structure_start_chunk",net.minecraft.world.level.ChunkPos.asLong(id.chunkX(),id.chunkZ()));
        tag.putString("state", "PREPARING"); tag.putBoolean("pending", true);
        players.put(player.toString(), tag); setDirty();
    }
    void clearReservation(UUID player) {
        if (chosen(player)) return;
        CompoundTag tag = players.getCompound(player.toString()); tag.remove("instance"); tag.remove("entry_id");
        tag.remove("structure_id"); tag.remove("structure_start_chunk"); tag.remove("dimension");
        tag.putString("state", "PENDING"); setDirty();
    }
    boolean awaitingReveal(UUID id) { return players.getCompound(id.toString()).getString("state").equals("ARRIVED"); }
    void resumeArrival(UUID id) {
        if(!unsupported && chosen(id)) { players.getCompound(id.toString()).putString("state","ARRIVED"); setDirty(); }
    }
    CompoundTag selection(UUID id) { return players.getCompound(id.toString()).copy(); }
    void revealed(UUID id) {
        if (chosen(id)) { players.getCompound(id.toString()).putString("state", "COMPLETE"); setDirty(); }
    }
    @Override public CompoundTag save(CompoundTag tag) {
        if (!unsupported) { tag.putInt("schema_version",CURRENT_SCHEMA); tag.put("players", players.copy()); tag.put("candidate_pool", pool.save()); }
        return tag;
    }
}
