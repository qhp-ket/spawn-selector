package dev.tide.spawnselector;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Packets {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(SpawnSelector.ID, "main"), () -> "6", "6"::equals, "6"::equals);
    public record Card(String id, Component name, Component description, String icon, String preview) {
        static Card of(SpawnEntry e) { return new Card(e.id().toString(), e.name(), e.description(),
            e.icon().toString(), e.preview() == null ? "" : e.preview().toString()); }
    }
    public record Layout(int entriesPerPage, int panelWidth, int cardHeight, boolean showIcons, boolean showPreviews,
                         int previewWidth, int previewHeight, boolean allowPermanentSkip, Component title,
                         Component confirmLabel, Component backLabel, Component skipLabel, Component emptyMessage, Component footer) {
        static Layout decode(FriendlyByteBuf b) { return new Layout(b.readVarInt(),b.readVarInt(),b.readVarInt(),
            b.readBoolean(),b.readBoolean(),b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readComponent(),
            b.readComponent(),b.readComponent(),b.readComponent(),b.readComponent(),b.readComponent()); }
        void encode(FriendlyByteBuf b) { b.writeVarInt(entriesPerPage);b.writeVarInt(panelWidth);b.writeVarInt(cardHeight);
            b.writeBoolean(showIcons);b.writeBoolean(showPreviews);b.writeVarInt(previewWidth);b.writeVarInt(previewHeight);
            b.writeBoolean(allowPermanentSkip);b.writeComponent(title);b.writeComponent(confirmLabel);b.writeComponent(backLabel);b.writeComponent(skipLabel);
            b.writeComponent(emptyMessage);b.writeComponent(footer); }
    }
    // State: 0 preparation; 1 options; 2 progress; 3 close/release; 4 arm; 5 recovery; 6 await client world render.
    public record View(int state, Component message, List<Card> cards, Layout layout,
                       String revealDimension, long revealPosition, String revealToken) {
        public View(int state, Component message, List<Card> cards, Layout layout) {
            this(state, message, cards, layout, "", 0L, "");
        }
        static View decode(FriendlyByteBuf b) {
            int state = b.readVarInt(); Component message = b.readComponent(); int n = b.readVarInt();
            if (state < 0 || state > 7 || n < 0 || n > 64) throw new IllegalArgumentException("Bad selector packet");
            List<Card> cards = new ArrayList<>();
            for (int i=0; i<n; i++) cards.add(new Card(b.readUtf(256), b.readComponent(), b.readComponent(), b.readUtf(256), b.readUtf(256)));
            return new View(state, message, List.copyOf(cards), Layout.decode(b), b.readUtf(256), b.readLong(), b.readUtf(64));
        }
        void encode(FriendlyByteBuf b) {
            b.writeVarInt(state); b.writeComponent(message); b.writeVarInt(cards.size());
            for (Card c : cards) { b.writeUtf(c.id(),256); b.writeComponent(c.name()); b.writeComponent(c.description()); b.writeUtf(c.icon(),256); b.writeUtf(c.preview(),256); }
            layout.encode(b);
            b.writeUtf(revealDimension,256); b.writeLong(revealPosition); b.writeUtf(revealToken,64);
        }
        void handle(Supplier<NetworkEvent.Context> context) {
            var ctx = context.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSelection.receive(this)));
            ctx.setPacketHandled(true);
        }
    }
    // Only a selection ID or readiness/escape action; never coordinates from the client.
    public record Action(String id) {
        static Action decode(FriendlyByteBuf b) { return new Action(b.readUtf(256)); }
        void encode(FriendlyByteBuf b) { b.writeUtf(id,256); }
        void handle(Supplier<NetworkEvent.Context> context) {
            var ctx = context.get();
            ctx.enqueueWork(() -> { if (ctx.getSender() != null) SelectionService.action(ctx.getSender(), id); });
            ctx.setPacketHandled(true);
        }
    }
    static void register() {
        CHANNEL.registerMessage(0, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    static void send(ServerPlayer p, View view) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), view); }
    public static void act(String id) { CHANNEL.sendToServer(new Action(id)); }
}
