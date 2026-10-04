package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;

/** Real NBT round trips and legacy migration without opening any world. */
public final class SavedDataTest {
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static PlayerSelections load(CompoundTag tag) throws Exception {
        var constructor=PlayerSelections.class.getDeclaredConstructor(CompoundTag.class);
        constructor.setAccessible(true); return constructor.newInstance(tag);
    }
    public static void main(String[] args) throws Exception {
        UUID alice=UUID.randomUUID(), bob=UUID.randomUUID();
        CompoundTag legacy=new CompoundTag(), players=new CompoundTag(), record=new CompoundTag();
        record.putString("entry","spawnselector:village"); record.putString("dimension","minecraft:overworld");
        record.putLong("position",new BlockPos(-400,72,640).asLong()); players.put(alice.toString(),record);
        legacy.putInt("schema_version",1); legacy.put("players",players);
        PlayerSelections migrated=load(legacy);
        check(migrated.chosen(alice) && !migrated.chosen(bob),"Legacy player remains completed without forcing a new choice");
        check(migrated.selection(alice).getString("state").equals("LEGACY_COMPLETE"),"Unknown old instance stays legacy completed");
        check(migrated.selection(alice).getLong("spawn_pos")==record.getLong("position"),"Legacy position survives");
        CompoundTag current=migrated.save(new CompoundTag());
        check(current.getInt("schema_version")==2 && load(current).chosen(alice),"Schema 2 round trip");
        migrated.resumeArrival(alice);
        check(migrated.chosen(alice) && migrated.awaitingReveal(alice),"Stale holding NBT recovery resumes ACK without discarding a completed selection");
        migrated.revealed(alice);
        check(!migrated.awaitingReveal(alice),"Recovered arrival finishes normally");
        var id=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",-25,40);
        CompoundTag candidate=StructureCandidatePool.identity(id);
        candidate.putString("state","PREPARING"); candidate.putInt("capacity",2);
        ListTag targets=new ListTag(); targets.add(StringTag.valueOf("spawnselector:village|minecraft:overworld|#minecraft:village"));
        candidate.put("targets",targets);
        ListTag claims=new ListTag();
        for(UUID player:List.of(alice,bob)) {
            CompoundTag claim=new CompoundTag(); claim.putUUID("player",player); claim.putBoolean("completed",player.equals(alice)); claims.add(claim);
        }
        candidate.put("claims",claims);
        CompoundTag pool=new CompoundTag(); ListTag instances=new ListTag(); instances.add(candidate); pool.put("instances",instances);
        current.put("candidate_pool",pool);
        ListTag completedDiscoveries=new ListTag();completedDiscoveries.add(StringTag.valueOf("completed-partial-search"));
        pool.put("completed_discoveries",completedDiscoveries);
        CompoundTag reloaded=load(current).save(new CompoundTag()).getCompound("candidate_pool");
        CompoundTag restored=reloaded.getList("instances",Tag.TAG_COMPOUND).getCompound(0);
        check(restored.getList("claims",Tag.TAG_COMPOUND).size()==1,"Restart releases pending Bob and retains completed Alice");
        check(restored.getString("state").equals("DISCOVERED"),"Interrupted preparation recovers");
        check(StructureCandidatePool.readIdentity(restored).equals(id),"Stable negative start chunk identity round trip");
        check(reloaded.getList("completed_discoveries",Tag.TAG_STRING).getString(0).equals("completed-partial-search"),"Partial/empty completed discovery cache survives a restart");
        var entryJson=com.google.gson.JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"#minecraft:village\"}").getAsJsonObject();
        var entry=SpawnEntry.parse(new net.minecraft.resources.ResourceLocation("spawnselector:village"),entryJson);
        String fingerprint=StructureCandidatePool.discoveryKey(entry);
        entryJson.addProperty("capacity_per_instance",1);
        check(fingerprint.equals(StructureCandidatePool.discoveryKey(SpawnEntry.parse(entry.id(),entryJson))),"Capacity-only changes reuse discovery");
        entryJson.addProperty("candidate_count",4);
        check(!fingerprint.equals(StructureCandidatePool.discoveryKey(SpawnEntry.parse(entry.id(),entryJson))),"Increasing candidate count invalidates discovery completion");
        entryJson.addProperty("candidate_count",3);entryJson.addProperty("search_radius",64);
        check(!fingerprint.equals(StructureCandidatePool.discoveryKey(SpawnEntry.parse(entry.id(),entryJson))),"Search changes invalidate discovery completion");
        current.putInt("schema_version",999);
        try { load(current); throw new AssertionError("Future schema must be protected"); }
        catch(java.lang.reflect.InvocationTargetException ex) { check(ex.getCause() instanceof UnsupportedOperationException,"Future schema is disabled safely by get()"); }
        System.out.println("PASS: real NBT legacy upgrade, player state, candidate persistence, interrupted reservation recovery, future-schema guard");
    }
}
