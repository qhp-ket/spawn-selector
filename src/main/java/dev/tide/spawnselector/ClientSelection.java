package dev.tide.spawnselector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SpawnSelector.ID, value = Dist.CLIENT)
public final class ClientSelection {
    private static boolean probe;
    private static int idleTicks;
    private static boolean armed;
    private static boolean revealWaiting, revealSent;
    private static int revealReadyTicks;
    private static Packets.View pending, active;
    public static void receive(Packets.View view) {
        Minecraft mc = Minecraft.getInstance();
        switch (view.state()) {
            case 4 -> {
                armed = true;
                probe = true;
                idleTicks = 0;
            }
            case 0 -> {
                probe = false; idleTicks = 0;
                if (mc.screen == null)
                    mc.setScreen(new SpawnSelectionScreen(new Packets.View(2, Component.translatable("spawnselector.message.preparing"), java.util.List.of(), view.layout())));
            }
            case 1, 2, 5, 6, 7 -> {
                if(view.state()==6) {
                    revealWaiting=true; revealSent=false; revealReadyTicks=0;
                }
                // Always retain the newest server state. It may be used later when another
                // mod closes its screen, even if this packet must not open a screen now.
                active = view;
                if (mc.screen instanceof SpawnSelectionScreen screen) {
                    screen.update(view);
                } else if (view.state() == 1 || view.state()==7 || view.state()==5 || view.state()==6 || !mc.hasSingleplayerServer()) {
                    // A single-player locating/searching state must not reopen a pause screen;
                    // the confirm action closes it so the integrated server can keep ticking.
                    pending = view;
                }
            }
            case 3 -> {
                probe = false; armed = false; pending = null; active = null; idleTicks = 0;
                revealWaiting=false; revealSent=false; revealReadyTicks=0;
                if (mc.screen instanceof SpawnSelectionScreen) mc.setScreen(null);
            }
            default -> { }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) { idleTicks = 0; return; }
        if(revealWaiting && !revealSent) {
            boolean atDestination=active != null && active.state()==6 && !active.revealToken().isEmpty()
                && mc.level.dimension().location().toString().equals(active.revealDimension())
                && mc.player.blockPosition().distSqr(net.minecraft.core.BlockPos.of(active.revealPosition())) <= 4;
            boolean ready=atDestination && mc.level.hasChunkAt(mc.player.blockPosition())
                && mc.levelRenderer.isChunkCompiled(mc.player.blockPosition());
            revealReadyTicks=ready ? revealReadyTicks+1 : 0;
            if(revealReadyTicks>=2) { revealSent=true; Packets.act("@world_ready:"+active.revealToken()); }
        }
        // A dimension transfer temporarily replaces the selector with Mojang's loading
        // screen, then may restore the old selector instance. Apply the newest state
        // before treating that restored screen as an unrelated modal screen.
        if (mc.screen instanceof SpawnSelectionScreen screen && pending != null) {
            Packets.View view = pending;
            pending = null;
            active = view;
            screen.update(view);
            idleTicks = 0;
        }
        // The vanilla dimension-loading screen can hide the selector while the
        // server sends state=3. If Minecraft restores that old selector afterward,
        // all selector state is already cleared and the screen must not come back.
        if (mc.screen instanceof SpawnSelectionScreen && !armed && pending == null && active == null) {
            mc.setScreen(null);
            idleTicks = 0;
            return;
        }
        if (mc.screen != null) { idleTicks = 0; return; }
        if (pending != null) {
            Packets.View view = pending;
            pending = null;
            mc.setScreen(new SpawnSelectionScreen(view));
            return;
        }
        if (probe) { probe = false; Packets.act("@ready"); }
        else if (++idleTicks < 10) return;
        if (pending != null || active != null) {
            Packets.View view = pending != null ? pending : active;
            pending = null; mc.setScreen(new SpawnSelectionScreen(view));
        }
    }
    /**
     * Origins can close its screen between two client ticks. Cancel every HUD overlay
     * for that one-frame gap and paint the same opaque texture as the Origins selector,
     * so the world framebuffer can never flash through.
     */
    @SubscribeEvent public static void coverPendingWorld(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!armed || mc.player == null || mc.level == null || mc.screen != null) return;
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        // This is the same texture used by Origins/vanilla Screen.renderDirtBackground.
        graphics.setColor(0.25f, 0.25f, 0.25f, 1.0f);
        graphics.blit(new ResourceLocation("minecraft", "textures/gui/options_background.png"),
            0, 0, 0, 0, width, height, 32, 32);
        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
    static void clear() {
        probe=false; armed=false; pending=null; active=null; idleTicks=0;
        revealWaiting=false; revealSent=false; revealReadyTicks=0;
    }
}
