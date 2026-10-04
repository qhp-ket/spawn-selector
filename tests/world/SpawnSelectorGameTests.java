package dev.tide.spawnselectortests;

import dev.tide.spawnselector.*;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.*;

/** Development-only real-world tests; these classes are never included in runtime jars. */
@Mod("spawnselectortests")
@GameTestHolder("spawnselectortests")
@PrefixGameTestTemplate(false)
public final class SpawnSelectorGameTests {
    public SpawnSelectorGameTests() {}
    private static SpawnEntry entry(int capacity) {
        return TestAccess.call(SpawnEntry.class,"parse",new ResourceLocation("spawnselector:test"),JsonParser.parseString(
            "{\"locator\":\"minecraft_structure\",\"target\":\"minecraft:village_plains\",\"capacity_per_instance\":"+capacity+"}").getAsJsonObject());
    }
    private static void ground(GameTestHelper helper,BlockPos pos) {
        for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) {
            var column=pos.offset(dx,0,dz);
            helper.getLevel().setBlockAndUpdate(column.below(),Blocks.STONE.defaultBlockState());
            for(int dy=0;dy<6;dy++) helper.getLevel().setBlockAndUpdate(column.above(dy),Blocks.AIR.defaultBlockState());
        }
        SpawnSelector.LOG.info("SAFETY_FIXTURE pos={} ground={} surface={} sky={}",pos,helper.getLevel().getBlockState(pos.below()),
            helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,pos.getX(),pos.getZ()),helper.getLevel().canSeeSky(pos));
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void safeFlatHazardsAndActualBody(GameTestHelper helper) {
        var level=helper.getLevel(); BlockPos pos=helper.absolutePos(new BlockPos(8,1,8));
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"SafetyTest"));
        player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        for(int x=(pos.getX()-5)>>4;x<=(pos.getX()+5)>>4;x++)
            for(int z=(pos.getZ()-5)>>4;z<=(pos.getZ()+5)>>4;z++) level.getChunk(x,z);
        ground(helper,pos);
        helper.runAfterDelay(20,()->{
        var outside=new BoundingBox(pos.getX()+64,level.getMinBuildHeight(),pos.getZ()+64,pos.getX()+96,level.getMaxBuildHeight()-1,pos.getZ()+96);
        var validator=TestAccess.create("SpawnSafetyValidator",level,outside,true);
        SpawnSelector.LOG.info("SAFETY_FIXTURE body={} playerLevel={} nocol={} fluid={}",player.getBoundingBox(),player.serverLevel().dimension(),
            level.noCollision(player,player.getBoundingBox().inflate(.05,0,.05)),level.containsAnyLiquid(player.getBoundingBox()));
        helper.assertTrue(TestAccess.<Boolean>call(validator,"safe",player,pos,.6f,1.8f),"Flat supported ground should be safe");
        level.setBlockAndUpdate(pos,Blocks.WATER.defaultBlockState());
        helper.assertTrue(!TestAccess.<Boolean>call(validator,"safe",player,pos,.6f,1.8f),"Water must be rejected");
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.below(),Blocks.MAGMA_BLOCK.defaultBlockState());
        helper.assertTrue(!TestAccess.<Boolean>call(validator,"safe",player,pos,.6f,1.8f),"Unsafe footing must be rejected");
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos.east().below(),Blocks.AIR.defaultBlockState());
        helper.assertTrue(!TestAccess.<Boolean>call(validator,"safe",player,pos,.6f,1.8f),"A cliff in the landing pad must be rejected");
        level.setBlockAndUpdate(pos.east().below(),Blocks.STONE.defaultBlockState());
        player.setBoundingBox(new AABB(pos.getX()-1.5,pos.getY(),pos.getZ()-1.5,pos.getX()+2.5,pos.getY()+2,pos.getZ()+2.5));
        level.setBlockAndUpdate(pos.east(),Blocks.STONE.defaultBlockState());
        helper.assertTrue(!TestAccess.<Boolean>call(validator,"safe",player,pos,.6f,1.8f),"Actual modded bounding box must detect collision beyond declared dimensions");
        helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void footprintAndOpenSkyPolicy(GameTestHelper helper) {
        var level=helper.getLevel(); BlockPos pos=helper.absolutePos(new BlockPos(8,1,8));
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"SkyTest"));
        player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        for(int x=(pos.getX()-5)>>4;x<=(pos.getX()+5)>>4;x++)
            for(int z=(pos.getZ()-5)>>4;z<=(pos.getZ()+5)>>4;z++) level.getChunk(x,z);
        ground(helper,pos);
        helper.runAfterDelay(20,()->{
        helper.assertTrue(level.canSeeSky(pos),"Fixture sky light must be ready before testing policy");
        var outside=new BoundingBox(pos.getX()+64,0,pos.getZ()+64,pos.getX()+96,100,pos.getZ()+96);
        var inside=new BoundingBox(pos.getX()-10,-60,pos.getZ()-10,pos.getX()+10,-50,pos.getZ()+10);
        helper.assertTrue(!TestAccess.<Boolean>call(TestAccess.create("SpawnSafetyValidator",level,inside,false),"safe",player,pos,.6f,1.8f),"Target footprint excludes XZ even when structure Y is underground");
        level.setBlockAndUpdate(pos.above(3),Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(20,()->{
        helper.assertTrue(!level.canSeeSky(pos),"Canopy sky-light update must be ready");
        helper.assertTrue(!TestAccess.<Boolean>call(TestAccess.create("SpawnSafetyValidator",level,outside,true),"safe",player,pos,.6f,1.8f),"Open-sky=true rejects a canopy");
        helper.assertTrue(TestAccess.<Boolean>call(TestAccess.create("SpawnSafetyValidator",level,outside,false),"safe",player,pos,.6f,1.8f),"Open-sky=false allows a safe canopy with actual local support");
        helper.succeed();
        });
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void actualPoolCapacityAndRestart(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID(),carol=UUID.randomUUID();
        for(int capacity:new int[]{1,2,-1}) {
            var data=TestAccess.load(new CompoundTag()); var entry=entry(capacity);
            var a=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",1000+capacity,2000);
            var b=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",2000+capacity,2000);
            var pool=TestAccess.pool(data); TestAccess.call(pool,"discover",server,entry,List.of(a,b));
            helper.assertTrue(TestAccess.<Boolean>call(pool,"claim",server,entry,a,alice),"Alice should claim A");
            helper.assertTrue(TestAccess.<Boolean>call(pool,"claim",server,entry,a,bob)==(capacity!=1),"Same instance obeys capacity");
            helper.assertTrue(TestAccess.<Boolean>call(pool,"claim",server,entry,a,carol)==(capacity==-1),"Last slot is never overbooked");
            TestAccess.call(pool,"complete",server,a,alice); TestAccess.call(pool,"release",server,alice);
            helper.assertTrue(((ClaimLedger)TestAccess.field(TestAccess.<List<?>>call(pool,"all",entry).get(0),"claims")).completed(alice),"Completed claims are permanent");
            TestAccess.call(pool,"release",server,bob);
            helper.assertTrue(TestAccess.<Boolean>call(pool,"claim",server,entry,b,bob),"Same type different instance can be independently claimed");
            var recovered=TestAccess.load(data.save(new CompoundTag()));
            helper.assertTrue(((ClaimLedger)TestAccess.field(TestAccess.<List<?>>call(TestAccess.pool(recovered),"all",entry).get(0),"claims")).completed(alice),"Restart retains Alice");
            helper.assertTrue(((ClaimLedger)TestAccess.field(TestAccess.<List<?>>call(TestAccess.pool(recovered),"all",entry).get(1),"claims")).size()==0,"Restart releases unfinished Bob");
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1400)
    public static void multiVillageDiscovery(GameTestHelper helper) {
        var level=helper.getLevel(); var entry=entry(2);
        var locator=TestAccess.create("MultiStructureLocator",level,entry,new BlockPos(4000,64,4000));
        helper.succeedWhen(()->{
            TestAccess.call(locator,"step"); helper.assertTrue(TestAccess.<Boolean>call(locator,"done"),"Waiting for bounded discovery");
            helper.assertTrue(TestAccess.<List<StructureInstanceId>>call(locator,"results").size()==3,"Fixed normal world should yield three distinct village starts");
            helper.assertTrue(new HashSet<>(TestAccess.<List<StructureInstanceId>>call(locator,"results")).size()==3,"Instances must have distinct stable identity");
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void holdingAndDeferredState(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var holding=server.getLevel(SpawnSelectorDimensions.HOLDING);
        helper.assertTrue(holding!=null,"Holding dimension must exist on the dedicated test server");
        SpawnSelectorDimensions.ensurePlatform(holding);
        helper.assertTrue(holding.getBlockState(SpawnSelectorDimensions.HOLDING_POS.below()).is(Blocks.BARRIER),"Holding platform is ready");
        var state=TestAccess.call(TestAccess.type("WorldSpawnState"),"get",server.overworld());
        helper.assertTrue((Boolean)TestAccess.field(state,"deferred") && !(Boolean)TestAccess.field(state,"materialized") && !(Boolean)TestAccess.field(state,"spawnTicketActive"),"Structure flow must not restore vanilla START ticket");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1400)
    public static void concentricStrongholdDiscovery(GameTestHelper helper) {
        SpawnEntry entry=TestAccess.call(SpawnEntry.class,"parse",new ResourceLocation("spawnselector:rings_test"),
            JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"minecraft:stronghold\",\"candidate_count\":2}").getAsJsonObject());
        var locator=TestAccess.create("MultiStructureLocator",helper.getLevel(),entry,new BlockPos(4000,64,4000));
        helper.succeedWhen(()->{
            TestAccess.call(locator,"step"); helper.assertTrue(TestAccess.<Boolean>call(locator,"done"),"Waiting for ring start discovery");
            var results=TestAccess.<List<StructureInstanceId>>call(locator,"results");
            helper.assertTrue(results.size()==2 && new HashSet<>(results).size()==2,"Concentric rings should yield two distinct real stronghold starts");
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void targetFilteringAndBiomeViability(GameTestHelper helper) {
        var level=helper.getLevel();
        SpawnEntry impossible=TestAccess.call(SpawnEntry.class,"parse",new ResourceLocation("spawnselector:viability_test"),
            JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"minecraft:nether_fossil\"}").getAsJsonObject());
        helper.assertTrue(TestAccess.<net.minecraft.core.HolderSet<?>>call(StructureTargetResolver.class,"resolve",level,impossible).size()==0,
            "Registered Nether structure must be hidden in normal Overworld biome source");
        for(String exclusion:List.of("minecraft:village_*","#minecraft:village")) {
            SpawnEntry filtered=TestAccess.call(SpawnEntry.class,"parse",new ResourceLocation("spawnselector:filter_test"),
                JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"#minecraft:village\",\"exclusions\":[\""+exclusion+"\"]}").getAsJsonObject());
            helper.assertTrue(TestAccess.<net.minecraft.core.HolderSet<?>>call(StructureTargetResolver.class,"resolve",level,filtered).size()==0,
                "Wildcard and tag exclusions must filter the target before locate");
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=700)
    public static void densePerimeterFindsMissedGap(GameTestHelper helper) {
        var level=helper.getLevel(); var pos=helper.absolutePos(new BlockPos(8,1,8)).offset(0,0,100);
        // A 40-block ring gives real gaps wider than the required 3x3 support pad.
        // Keep the synthetic terrain separate from neighboring fixture templates.
        var bounds=new BoundingBox(pos.getX()-16,pos.getY()-5,pos.getZ()-16,pos.getX()+16,pos.getY()+5,pos.getZ()+16);
        SpawnEntry option=TestAccess.call(SpawnEntry.class,"parse",new ResourceLocation("spawnselector:dense_test"),
            JsonParser.parseString("{\"locator\":\"minecraft_structure\",\"target\":\"minecraft:village_plains\",\"min_distance\":4,\"max_distance\":4,\"require_open_sky\":false}").getAsJsonObject());
        for(int x=pos.getX()-24;x<=pos.getX()+24;x++) for(int z=pos.getZ()-24;z<=pos.getZ()+24;z++) {
            level.setBlockAndUpdate(new BlockPos(x,pos.getY()-1,z),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++) level.setBlockAndUpdate(new BlockPos(x,pos.getY()+y,z),Blocks.AIR.defaultBlockState());
        }
        var sparse=Perimeter.candidates(bounds.minX(),bounds.minZ(),bounds.maxX(),bounds.maxZ(),4,4,"north");
        for(var point:sparse) level.setBlockAndUpdate(new BlockPos(point.x(),pos.getY()-1,point.z()),Blocks.MAGMA_BLOCK.defaultBlockState());
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"DenseTest"));
        player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        var finder=new SafeSpawnFinder(level,bounds,option,player);
        helper.succeedWhen(()->{
            BlockPos found=finder.step(player);
            helper.assertTrue(found!=null,"Waiting for bounded sparse then dense search");
            helper.assertTrue(sparse.stream().noneMatch(p->p.x()==found.getX()&&p.z()==found.getZ()),"Dense phase must find a gap not present among sparse candidates");
            finder.close();
        });
    }
}
