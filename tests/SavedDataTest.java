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
    static SpawnEntry poolEntry(int count, int capacity) {
        var json=com.google.gson.JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"#minecraft:village\"}").getAsJsonObject();
        json.addProperty("candidate_count",count);
        json.addProperty("capacity_per_instance",capacity);
        return SpawnEntry.parse(new net.minecraft.resources.ResourceLocation("spawnselector:village"),json);
    }
    static void candidateAvailability() throws Exception {
        for(int capacity:List.of(1,2)) {
            var original=poolEntry(5,capacity);
            ListTag instances=new ListTag();
            for(int i=0;i<5;i++) {
                var id=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",i,0);
                CompoundTag candidate=StructureCandidatePool.identity(id);
                candidate.putString("state","READY"); candidate.putInt("capacity",capacity);
                ListTag targets=new ListTag(); targets.add(StringTag.valueOf(StructureCandidatePool.target(original)));
                candidate.put("targets",targets);
                ListTag claims=new ListTag();
                if(i<2) for(int slot=0;slot<capacity;slot++) {
                    CompoundTag claim=new CompoundTag(); claim.putUUID("player",new UUID(i+1,slot+1));
                    claim.putBoolean("completed",true); claims.add(claim);
                }
                candidate.put("claims",claims); instances.add(candidate);
            }
            CompoundTag root=new CompoundTag(), poolTag=new CompoundTag();
            root.putInt("schema_version",2); poolTag.put("instances",instances); root.put("candidate_pool",poolTag);
            // Exercise the real SavedData load/save/load path, including permanent claims and order.
            var pool=load(load(root).save(new CompoundTag())).pool;
            var reduced=poolEntry(2,capacity);
            var eligible=pool.all(reduced); // Fixture contains only this target's valid structure IDs.
            check(eligible.size()==5,"Reducing count preserves historical instances");
            check(eligible.get(0).claims.size()==capacity && eligible.get(1).claims.size()==capacity,
                "Completed claims keep the leading instances full after reload");
            check(pool.availableFromEligible(eligible,original).equals(eligible.subList(2,5)),
                "Unchanged count returns all three available instances");
            check(pool.availableFromEligible(eligible,reduced).equals(eligible.subList(2,4)),
                "Reducing count from five to two skips full A/B and returns C/D");
            eligible.get(2).state=StructureCandidatePool.State.FAILED;
            check(pool.availableFromEligible(eligible,reduced).equals(eligible.subList(3,5)),
                "Failed C does not consume a returned candidate slot");
            eligible.get(3).state=StructureCandidatePool.State.FAILED;
            check(pool.availableFromEligible(eligible,reduced).equals(List.of(eligible.get(4))),
                "Fewer available instances returns the remaining E");
            eligible.get(4).state=StructureCandidatePool.State.FAILED;
            check(pool.availableFromEligible(eligible,reduced).isEmpty(),"Full or failed pool returns empty");
            check(pool.availableFromEligible(eligible,poolEntry(2,-1)).equals(eligible.subList(0,2)),
                "Unlimited capacity permits populated A/B but still excludes failed instances and limits count");
            check(pool.availableFromEligible(List.of(),reduced).isEmpty(),"Empty eligible pool remains empty");
        }
    }
    public static void main(String[] args) throws Exception {
        candidateAvailability();
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
        System.out.println("PASS: real NBT legacy upgrade, player state, candidate persistence, reduced-count availability, interrupted reservation recovery, future-schema guard");
    }
}
