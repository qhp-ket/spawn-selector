package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;

/** World-owned pool. Claims and player records share one SavedData save unit. */
public final class StructureCandidatePool {
    public enum State { DISCOVERED, PREPARING, READY, FAILED }
    static final class Candidate {
        final StructureInstanceId id;
        final Set<String> targets = new LinkedHashSet<>();
        final Map<String,Integer> policies = new LinkedHashMap<>();
        final ClaimLedger claims = new ClaimLedger();
        State state = State.DISCOVERED;
        int capacity;
        BlockPos spawn;
        Candidate(StructureInstanceId id, int capacity) { this.id = id; this.capacity = capacity; }
    }
    private final PlayerSelections data;
    private final Map<StructureInstanceId, Candidate> candidates = new LinkedHashMap<>();
    private final Set<String> completedDiscoveries = new LinkedHashSet<>();
    StructureCandidatePool(PlayerSelections data) { this.data = data; }
    private void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Candidate pool mutation requires server thread");
    }
    static String target(SpawnEntry entry) { return entry.id() + "|" + entry.dimension() + "|" + entry.target(); }
    static String discoveryKey(SpawnEntry entry) {
        com.google.gson.JsonArray key=new com.google.gson.JsonArray();
        key.add(target(entry));key.add(entry.searchRadius());key.add(entry.candidateCount());
        entry.exclusions().forEach(key::add);return key.toString();
    }
    private String resolvedDiscoveryKey(MinecraftServer server,SpawnEntry entry) {
        var level=Locators.level(server,entry);
        if(level==null)return discoveryKey(entry)+"|missing_dimension";
        String structures=StructureTargetResolver.resolve(level,entry).stream()
            .map(holder->holder.unwrapKey().orElseThrow().location().toString()).sorted().collect(java.util.stream.Collectors.joining(","));
        return discoveryKey(entry)+"|"+structures;
    }
    boolean discoveryComplete(MinecraftServer server,SpawnEntry entry) {
        thread(server);return completedDiscoveries.contains(resolvedDiscoveryKey(server,entry));
    }
    void finishDiscovery(MinecraftServer server,SpawnEntry entry) {
        thread(server);
        if(completedDiscoveries.add(resolvedDiscoveryKey(server,entry))) data.setDirty();
    }
    void discover(MinecraftServer server, SpawnEntry entry, List<StructureInstanceId> found) {
        thread(server);
        int count = eligible(server, entry).size();
        for (var id : found) {
            Candidate c = candidates.get(id);
            if ((c == null || !c.targets.contains(target(entry))) && count >= entry.candidateCount()) continue;
            if (c == null) { c = new Candidate(id, entry.capacityPerInstance()); candidates.put(id, c); }
            if (c.targets.add(target(entry))) count++;
            refreshPolicy(c, entry);
        }
        data.setDirty();
    }
    List<Candidate> all(SpawnEntry entry) {
        return candidates.values().stream().filter(c -> c.targets.contains(target(entry))).toList();
    }
    List<Candidate> available(MinecraftServer server, SpawnEntry entry) {
        return availableFromEligible(eligible(server, entry), entry);
    }
    // Shares selection policy with headless persistence tests; live callers resolve on the server thread.
    List<Candidate> availableFromEligible(List<Candidate> eligible, SpawnEntry entry) {
        // Reload can reduce the limit below the number of persisted candidates.
        return eligible.stream()
            .filter(c -> c.state != State.FAILED && c.claims.hasRoom(effectiveCapacity(c, entry)))
            .limit(entry.candidateCount()).toList();
    }
    List<Candidate> eligible(MinecraftServer server, SpawnEntry entry) {
        thread(server);
        var level = Locators.level(server, entry);
        if (level == null) return List.of();
        var resolved = StructureTargetResolver.resolve(level, entry).stream()
            .map(h -> h.unwrapKey().orElseThrow().location().toString()).collect(java.util.stream.Collectors.toSet());
        return all(entry).stream().filter(c -> resolved.contains(c.id.structureId())).toList();
    }
    private int effectiveCapacity(Candidate c, SpawnEntry entry) {
        refreshPolicy(c,entry);
        return c.capacity;
    }
    private void refreshPolicy(Candidate c,SpawnEntry entry) {
        Integer old=c.policies.put(target(entry),entry.capacityPerInstance());
        c.capacity=c.policies.values().stream().filter(v->v!=-1).mapToInt(Integer::intValue).min().orElse(-1);
        if(!Objects.equals(old,entry.capacityPerInstance())) data.setDirty();
    }
    boolean claim(MinecraftServer server, SpawnEntry entry, StructureInstanceId id, UUID player) {
        thread(server);
        Candidate c = candidates.get(id);
        if (c == null || !available(server, entry).contains(c)) return false;
        release(server, player);
        c.capacity = effectiveCapacity(c, entry);
        if (!c.claims.claim(player, c.capacity)) return false;
        c.state = State.PREPARING;
        data.reserve(player, entry.id().toString(), id);
        data.setDirty();
        SpawnSelector.LOG.info("Player {} claimed {} ({}/{})", player, id.key(), c.claims.size(), c.capacity);
        return true;
    }
    void ready(MinecraftServer server, StructureInstanceId id, BlockPos spawn) {
        thread(server);
        Candidate c = Objects.requireNonNull(candidates.get(id));
        c.spawn = spawn.immutable(); c.state = State.READY; data.setDirty();
    }
    void complete(MinecraftServer server, StructureInstanceId id, UUID player) {
        thread(server);
        Candidate c = Objects.requireNonNull(candidates.get(id));
        c.claims.complete(player); c.state = State.READY; data.setDirty();
    }
    void release(MinecraftServer server, UUID player) {
        thread(server);
        for (Candidate c : candidates.values()) {
            c.claims.release(player);
            if (c.state == State.PREPARING && c.claims.snapshot().values().stream().noneMatch(completed -> !completed))
                c.state = c.spawn == null ? State.DISCOVERED : State.READY;
        }
        data.clearReservation(player); data.setDirty();
    }
    CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (Candidate c : candidates.values()) {
            CompoundTag tag = identity(c.id);
            tag.putString("state", c.state.name()); tag.putInt("capacity", c.capacity);
            if (c.spawn != null) tag.putLong("prepared_spawn", c.spawn.asLong());
            ListTag targets = new ListTag(); c.targets.forEach(t -> targets.add(StringTag.valueOf(t))); tag.put("targets", targets);
            CompoundTag policies=new CompoundTag(); c.policies.forEach(policies::putInt); tag.put("capacity_policies",policies);
            ListTag claims = new ListTag();
            c.claims.snapshot().forEach((uuid, completed) -> {
                CompoundTag claim = new CompoundTag(); claim.putUUID("player", uuid); claim.putBoolean("completed", completed); claims.add(claim);
            });
            tag.put("claims", claims); list.add(tag);
        }
        root.put("instances", list);
        ListTag discoveries=new ListTag(); completedDiscoveries.forEach(key->discoveries.add(StringTag.valueOf(key)));
        root.put("completed_discoveries",discoveries); return root;
    }
    void load(CompoundTag root) {
        for(Tag raw:root.getList("completed_discoveries",Tag.TAG_STRING)) completedDiscoveries.add(raw.getAsString());
        for (Tag raw : root.getList("instances", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag tag = (CompoundTag) raw;
                StructureInstanceId id = readIdentity(tag);
                int capacity = tag.getInt("capacity");
                if (capacity != -1 && capacity < 1) throw new IllegalArgumentException("Invalid saved capacity");
                Candidate c = new Candidate(id, capacity);
                c.state = State.valueOf(tag.getString("state"));
                if (c.state == State.PREPARING) c.state = State.DISCOVERED;
                if (tag.contains("prepared_spawn")) c.spawn = BlockPos.of(tag.getLong("prepared_spawn"));
                for (Tag t : tag.getList("targets", Tag.TAG_STRING)) c.targets.add(t.getAsString());
                CompoundTag policies=tag.getCompound("capacity_policies");
                for(String target:c.targets) c.policies.put(target,policies.contains(target)?policies.getInt(target):capacity);
                for (Tag t : tag.getList("claims", Tag.TAG_COMPOUND)) {
                    CompoundTag claim = (CompoundTag)t;
                    if (claim.hasUUID("player")) c.claims.restore(claim.getUUID("player"), claim.getBoolean("completed"));
                }
                c.claims.recover(); // Reservations cannot survive a restart without a live session.
                candidates.put(id, c);
            } catch (RuntimeException ex) { SpawnSelector.LOG.warn("Ignoring malformed saved candidate", ex); }
        }
    }
    static CompoundTag identity(StructureInstanceId id) {
        CompoundTag tag = new CompoundTag(); tag.putString("dimension", id.dimension()); tag.putString("structure_id", id.structureId());
        tag.putLong("structure_start_chunk", net.minecraft.world.level.ChunkPos.asLong(id.chunkX(), id.chunkZ())); return tag;
    }
    static StructureInstanceId readIdentity(CompoundTag tag) {
        String dimension = tag.getString("dimension"), structure = tag.getString("structure_id");
        if (net.minecraft.resources.ResourceLocation.tryParse(dimension) == null || net.minecraft.resources.ResourceLocation.tryParse(structure) == null)
            throw new IllegalArgumentException("Invalid instance identity");
        var pos = new net.minecraft.world.level.ChunkPos(tag.getLong("structure_start_chunk"));
        return new StructureInstanceId(dimension, structure, pos.x, pos.z);
    }
}
