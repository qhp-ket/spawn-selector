package dev.tide.spawnselector;

import com.google.gson.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.ModList;

public final class SpawnEntries extends SimpleJsonResourceReloadListener {
    private static Map<ResourceLocation, SpawnEntry> entries = Map.of();
    private static int lastOverflowWarning = -1;
    public SpawnEntries() { super(new Gson(), "spawn_entries"); }
    static void reloadListener(AddReloadListenerEvent e) { e.addListener(new SpawnEntries()); }
    @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> merged = new HashMap<>(data);
        try {
            JsonObject overrides = ExternalConfig.section(ExternalConfig.load(), "entries");
            overrides.entrySet().forEach(entry -> {
                try {
                    String rawId = entry.getKey().contains(":") ? entry.getKey() : SpawnSelector.ID + ":" + entry.getKey();
                    ResourceLocation id = new ResourceLocation(rawId);
                    JsonElement value = entry.getValue();
                    if (!value.isJsonObject()) throw new IllegalArgumentException("entry must be an object");
                    JsonElement bundled = merged.get(id);
                    if (bundled == null) bundled = ExternalConfig.legacyEntry(id.toString());
                    merged.put(id, bundled != null && bundled.isJsonObject()
                        ? ExternalConfig.merge(bundled.getAsJsonObject(), value.getAsJsonObject()) : value);
                } catch (RuntimeException ex) {
                    SpawnSelector.LOG.error("Invalid external spawn entry {}", entry.getKey(), ex);
                }
            });
        } catch (RuntimeException ex) {
            SpawnSelector.LOG.error("Invalid external spawnselector entries section", ex);
        }
        Map<ResourceLocation, SpawnEntry> loaded = new HashMap<>();
        merged.forEach((id, json) -> {
            try {
                if (!json.getAsJsonObject().has("enabled") || json.getAsJsonObject().get("enabled").getAsBoolean())
                    loaded.put(id, SpawnEntry.parse(id, json.getAsJsonObject()));
            } catch (RuntimeException ex) { SpawnSelector.LOG.error("Invalid spawn entry {}", id, ex); }
        });
        entries = Map.copyOf(loaded);
        lastOverflowWarning = -1;
    }
    static List<SpawnEntry> available(MinecraftServer server) {
        List<SpawnEntry> usable = entries.values().stream().filter(e -> e.requiredMod().isEmpty() || ModList.get().isLoaded(e.requiredMod()))
            .filter(e -> Locators.available(server, e))
            .sorted(Comparator.comparingInt(SpawnEntry::order).thenComparing(e -> e.id().toString()))
            .toList();
        if (usable.size() > 64 && lastOverflowWarning != usable.size()) {
            lastOverflowWarning = usable.size();
            SpawnSelector.LOG.warn("{} spawn entries are available; only the first 64 can be sent to clients", usable.size());
        }
        return usable.size() <= 64 ? usable : usable.subList(0, 64);
    }
}
