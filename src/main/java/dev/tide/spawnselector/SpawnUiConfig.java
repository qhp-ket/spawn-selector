package dev.tide.spawnselector;

import com.google.gson.*;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;

/** Datapack-controlled selector layout. The client receives the server's resolved values. */
public final class SpawnUiConfig extends SimpleJsonResourceReloadListener {
    private static volatile Packets.Layout current = defaults();
    public SpawnUiConfig() { super(new Gson(), "spawn_ui"); }
    static void reloadListener(AddReloadListenerEvent e) { e.addListener(new SpawnUiConfig()); }
    static Packets.Layout current() { return current; }
    static Packets.Layout defaults() {
        return new Packets.Layout(4, 420, 24, true, true, 128, 72, true,
            Component.translatable("spawnselector.ui.title"), Component.translatable("spawnselector.ui.confirm"),
            Component.translatable("spawnselector.ui.back"), Component.translatable("spawnselector.ui.skip"), Component.translatable("spawnselector.ui.empty"),
            Component.translatable("spawnselector.ui.footer"));
    }
    @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager rm, ProfilerFiller profiler) {
        JsonElement element = data.get(new ResourceLocation(SpawnSelector.ID, "layout"));
        try {
            JsonObject o = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            o = ExternalConfig.merge(o, ExternalConfig.section(ExternalConfig.load(), "layout"));
            current = new Packets.Layout(
                bounded(o,"entries_per_page",4,1,10), bounded(o,"panel_width",420,240,800),
                bounded(o,"card_height",24,20,40), GsonHelper.getAsBoolean(o,"show_icons",true),
                GsonHelper.getAsBoolean(o,"show_previews",true), bounded(o,"preview_width",128,32,512),
                bounded(o,"preview_height",72,18,288), GsonHelper.getAsBoolean(o,"allow_permanent_skip",true),
                component(o,"title",Component.translatable("spawnselector.ui.title"),512),
                component(o,"confirm_label",Component.translatable("spawnselector.ui.confirm"),512),
                component(o,"back_label",Component.translatable("spawnselector.ui.back"),512),
                component(o,"skip_label",Component.translatable("spawnselector.ui.skip"),512),
                component(o,"empty_message",Component.translatable("spawnselector.ui.empty"),1024),
                component(o,"footer",Component.translatable("spawnselector.ui.footer"),1024));
        } catch (RuntimeException ex) {
            SpawnSelector.LOG.error("Invalid spawn selector layout; using defaults", ex); current = defaults();
        }
    }
    static Component component(JsonObject o, String key, Component fallback, int maxJson) {
        if (!o.has(key)) return fallback;
        JsonElement value = o.get(key);
        Component result = value.isJsonPrimitive() ? Component.literal(value.getAsString()) : Component.Serializer.fromJson(value);
        if (result == null || Component.Serializer.toJson(result).length() > maxJson)
            throw new JsonParseException("Invalid or oversized component: " + key);
        return result;
    }
    private static int bounded(JsonObject o,String key,int fallback,int min,int max) {
        int value=GsonHelper.getAsInt(o,key,fallback);
        if(value<min||value>max)throw new JsonParseException(key+" outside "+min+".."+max);
        return value;
    }
}
