package dev.tide.spawnselectortests;

import dev.tide.spawnselector.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Optional instrumented client run; uses real screen button callbacks and renderer ACK. */
@Mod.EventBusSubscriber(modid="spawnselectortests",value=Dist.CLIENT)
public final class ClientRegression {
    private static boolean started, firstClicked, instanceClicked, done;
    private static volatile boolean initialChecked;
    private static volatile boolean originsWaitingChecked;
    private static boolean originClicked;
    private static final AtomicBoolean inspecting=new AtomicBoolean();
    private static volatile String failure="";
    private static volatile boolean completed;
    private static long began;
    private static net.minecraft.gametest.framework.MultipleTestTracker worldTests;
    private static String lastScreen="";
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        String scenario=System.getProperty("spawnselector.clientRegression","");
        boolean reopening=scenario.equals("restart") || scenario.equals("originsRestart") || scenario.equals("vanillaRestart");
        if(scenario.isEmpty() || event.phase!=TickEvent.Phase.END || done) return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if(!started) {
                String current=mc.screen==null?"null":mc.screen.getClass().getSimpleName();
                if(!current.equals(lastScreen)) { lastScreen=current; SpawnSelector.LOG.info("CLIENT_REGRESSION waiting screen={}",current); }
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding) {
                    onboarding.onClose(); return;
                }
            }
            if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
                started=true; began=System.nanoTime();
                mc.options.pauseOnLostFocus=false;
                String world=System.getProperty("spawnselector.testWorld","spawnselector-regression");
                SpawnSelector.LOG.info("CLIENT_REGRESSION START scenario={} world={}",scenario,world);
                if(reopening) mc.createWorldOpenFlows().loadLevel(mc.screen,world);
                else mc.createWorldOpenFlows().createFreshLevel(world,
                    new LevelSettings(world,GameType.SURVIVAL,false,Difficulty.PEACEFUL,false,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(72119L,true,false),WorldPresets::createNormalWorldDimensions);
                return;
            }
            if(!started) return;
            if(reopening && mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm
                && confirm.getTitle().equals(net.minecraft.network.chat.Component.translatable("selectWorld.backupQuestion.experimental"))) {
                press(confirm,net.minecraft.network.chat.CommonComponents.GUI_PROCEED); return;
            }
            if(reopening && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
                // This is our own disposable test world. Use the real backup-and-load callback.
                press(backup,net.minecraft.network.chat.Component.translatable("selectWorld.backupJoinConfirmButton"));
                return;
            }
            if(System.nanoTime()-began>240_000_000_000L) throw new AssertionError("Client regression exceeded 240 seconds");
            if(!failure.isEmpty()) throw new AssertionError(failure);
            if(completed) {
                done=true; SpawnSelector.LOG.info("CLIENT_REGRESSION PASS scenario={}",scenario);
                mc.stop(); return;
            }
            if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null) return;
            var server=mc.getSingleplayerServer(); var uuid=mc.player.getUUID();
            if(scenario.startsWith("origins") && mc.screen!=null
                && mc.screen.getClass().getName().equals("io.github.apace100.origins.screen.ChooseOriginScreen")) {
                if(!originsWaitingChecked && inspecting.compareAndSet(false,true)) server.execute(()->{
                    try {
                        var player=server.getPlayerList().getPlayer(uuid);
                        if(!SpawnSelectorDimensions.isHolding(player.serverLevel()) || dev.tide.spawnselector.compat.OriginsCompat.ready(player))
                            throw new AssertionError("Origins must remain in holding while selection is unfinished");
                        SpawnSelector.LOG.info("CLIENT_REGRESSION PASS ORIGINS_WAITING screen={} status={}",
                            "ChooseOriginScreen",dev.tide.spawnselector.compat.OriginsCompat.status());
                        if(scenario.equals("originsBroken")) {
                            var field=dev.tide.spawnselector.compat.OriginsCompat.class.getDeclaredField("get"); field.setAccessible(true);
                            field.set(null,ClientRegression.class.getMethod("brokenOriginsGet",net.minecraft.world.entity.Entity.class));
                            if(!dev.tide.spawnselector.compat.OriginsCompat.ready(player)
                                || dev.tide.spawnselector.compat.OriginsCompat.status()!=dev.tide.spawnselector.compat.OriginsCompat.Status.BROKEN)
                                throw new AssertionError("Broken installed Origins API must fail soft");
                            SpawnSelector.LOG.info("CLIENT_REGRESSION PASS ORIGINS_API_BROKEN_FALLBACK");
                        }
                        originsWaitingChecked=true;
                    } catch(Throwable ex) { failure=ex.toString(); }
                    finally { inspecting.set(false); }
                });
                if(originsWaitingChecked && !originClicked) {
                    originClicked=true; press(mc.screen,net.minecraft.network.chat.Component.translatable("origins.gui.select"));
                }
            }
            if(mc.screen instanceof SpawnSelectionScreen screen) {
                Field field=SpawnSelectionScreen.class.getDeclaredField("view"); field.setAccessible(true);
                var view=(Packets.View)field.get(screen);
                if(view.state()==1 && !initialChecked && inspecting.compareAndSet(false,true)) {
                    server.execute(()->{
                        try {
                            var player=server.getPlayerList().getPlayer(uuid);
                            if(player==null || !SpawnSelectorDimensions.isHolding(player.serverLevel())) throw new AssertionError("First player is not in holding");
                            if(scenario.equals("origins") && (!originsWaitingChecked || dev.tide.spawnselector.compat.OriginsCompat.status()!=dev.tide.spawnselector.compat.OriginsCompat.Status.READY))
                                throw new AssertionError("Spawn Selector must wait for actual Origins completion");
                            var state=TestAccess.call(Class.forName("dev.tide.spawnselector.WorldSpawnState"),"get",server.overworld());
                            int chunks=server.overworld().getChunkSource().getLoadedChunksCount();
                            if(!(Boolean)TestAccess.field(state,"deferred") || (Boolean)TestAccess.field(state,"materialized") || (Boolean)TestAccess.field(state,"spawnTicketActive") || chunks!=0)
                                throw new AssertionError("Startup generated vanilla spawn: chunks="+chunks);
                            initialChecked=true;
                            SpawnSelector.LOG.info("CLIENT_REGRESSION PASS INITIAL_HOLDING overworld_full={} Origins={}",chunks,dev.tide.spawnselector.compat.OriginsCompat.status());
                        } catch(Throwable ex) { failure=ex.toString(); }
                        finally { inspecting.set(false); }
                    });
                }
                if(view.state()==1 && initialChecked && !firstClicked) {
                    firstClicked=true;
                    press(screen,scenario.equals("vanilla")?view.layout().skipLabel():view.layout().confirmLabel());
                } else if(view.state()==7 && !instanceClicked) {
                    instanceClicked=true; press(screen,view.layout().confirmLabel());
                }
            }
            if(!(mc.screen instanceof SpawnSelectionScreen) && !mc.level.dimension().equals(SpawnSelectorDimensions.HOLDING)
                && (initialChecked || reopening) && inspecting.compareAndSet(false,true)) {
                server.execute(()->{
                    try {
                        var player=server.getPlayerList().getPlayer(uuid);
                        var data=TestAccess.<PlayerSelections>call(PlayerSelections.class,"get",server);
                        if(player==null || !data.chosen(uuid) || TestAccess.<Boolean>call(data,"awaitingReveal",uuid)) return;
                        var state=TestAccess.call(Class.forName("dev.tide.spawnselector.WorldSpawnState"),"get",server.overworld());
                        boolean vanilla=scenario.equals("vanilla") || scenario.equals("vanillaRestart");
                        if((Boolean)TestAccess.field(state,"materialized")!=vanilla || (Boolean)TestAccess.field(state,"spawnTicketActive")!=vanilla)
                            throw new AssertionError("Deferred state changed incorrectly after selection");
                        if(scenario.equals("originsRestart") && !dev.tide.spawnselector.compat.OriginsCompat.ready(player))
                            throw new AssertionError("Previously chosen Origins player must remain ready on reconnect");
                        if(!vanilla && !TestAccess.<net.minecraft.nbt.CompoundTag>call(data,"selection",uuid).contains("structure_start_chunk")) throw new AssertionError("Structure identity is missing");
                        if(!vanilla && !TestAccess.<net.minecraft.nbt.CompoundTag>call(TestAccess.pool(data),"save").getList("instances",net.minecraft.nbt.Tag.TAG_COMPOUND).stream().anyMatch(raw->{
                            var c=(net.minecraft.nbt.CompoundTag)raw;
                            return c.getList("claims",net.minecraft.nbt.Tag.TAG_COMPOUND).stream().anyMatch(r->{
                                var claim=(net.minecraft.nbt.CompoundTag)r; return claim.hasUUID("player")&&claim.getUUID("player").equals(uuid)&&claim.getBoolean("completed");
                            });
                        })) throw new AssertionError("Permanent candidate claim is missing");
                        if(!player.serverLevel().noCollision(player,player.getBoundingBox())) throw new AssertionError("Final player body collides");
                        if(worldTests==null) SpawnSelector.LOG.info("CLIENT_REGRESSION PASS FINAL selection={} position={} materialized={} START={} instance_page={}",
                            TestAccess.<net.minecraft.nbt.CompoundTag>call(data,"selection",uuid),player.blockPosition(),TestAccess.field(state,"materialized"),TestAccess.field(state,"spawnTicketActive"),instanceClicked);
                        if(scenario.equals("fixtures")) {
                            if(worldTests==null) {
                                var functions=net.minecraft.gametest.framework.GameTestRegistry.getAllTestFunctions();
                                if(functions.size()!=8) throw new AssertionError("Expected eight registered world fixtures, got "+functions.size());
                                worldTests=new net.minecraft.gametest.framework.MultipleTestTracker(net.minecraft.gametest.framework.GameTestRunner.runTests(
                                    functions,new net.minecraft.core.BlockPos(1000,220,1000),net.minecraft.world.level.block.Rotation.NONE,
                                    server.overworld(),net.minecraft.gametest.framework.GameTestTicker.SINGLETON,4));
                                SpawnSelector.LOG.info("CLIENT_REGRESSION WORLD_FIXTURES started={}",functions.size());
                                return;
                            }
                            if(!worldTests.isDone()) return;
                            if(worldTests.hasFailedRequired()) throw new AssertionError("World fixtures failed: "+worldTests.getFailedRequired().stream()
                                .map(t->t.getTestName()+": "+t.getError()).toList());
                            SpawnSelector.LOG.info("CLIENT_REGRESSION PASS WORLD_FIXTURES count={}",worldTests.getDoneCount());
                        }
                        completed=true;
                    } catch(Throwable ex) { failure=ex.toString(); }
                    finally { inspecting.set(false); }
                });
            }
        } catch(Throwable ex) {
            done=true; SpawnSelector.LOG.error("CLIENT_REGRESSION FAIL scenario={}",scenario,ex);
            mc.stop();
        }
    }
    public static net.minecraftforge.common.util.LazyOptional<?> brokenOriginsGet(net.minecraft.world.entity.Entity player) {
        throw new NoSuchMethodError("Deliberate installed Origins API failure fixture");
    }
    private static void press(net.minecraft.client.gui.screens.Screen screen,net.minecraft.network.chat.Component label) {
        var button=screen.children().stream().filter(w->w instanceof Button b && b.getMessage().equals(label)).map(w->(Button)w)
            .findFirst().orElseThrow(()->new AssertionError("Expected actual screen button: "+label));
        if(!button.active) throw new AssertionError("Regression tried to press an inactive button");
        button.onPress();
    }
}
