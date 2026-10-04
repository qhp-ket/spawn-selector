package dev.tide.spawnselector;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.network.chat.Component;

public record SpawnEntry(ResourceLocation id, Component name, Component description, ResourceLocation icon,
                         ResourceLocation dimension, String locator, String target, String requiredMod,
                         int order, int searchRadius, int minDistance, int maxDistance, String side,
                         ResourceLocation preview, int candidateCount, int capacityPerInstance,
                         boolean requireOpenSky, java.util.List<String> exclusions) {
    static SpawnEntry parse(ResourceLocation id, JsonObject o) {
        String locator = GsonHelper.getAsString(o, "locator");
        if (!locator.equals("minecraft_structure"))
            throw new IllegalArgumentException("Unknown locator: " + locator);
        int min = bounded(o, "min_distance", 8, 4, 64);
        int max = bounded(o, "max_distance", 48, min, 128);
        String side = GsonHelper.getAsString(o, "preferred_side", "north");
        if (!java.util.Set.of("north", "south", "east", "west").contains(side))
            throw new IllegalArgumentException("Invalid preferred_side");
        String target = limited(GsonHelper.getAsString(o, "target"), 256);
        String resource = target.startsWith("#") ? target.substring(1) : target;
        if (resource.isBlank() || ResourceLocation.tryParse(resource) == null)
            throw new IllegalArgumentException("Invalid structure target: " + target);
        ResourceLocation dimension = new ResourceLocation(GsonHelper.getAsString(o, "dimension", "minecraft:overworld"));
        if (dimension.equals(new ResourceLocation("minecraft:the_nether")))
            throw new IllegalArgumentException("dimension has a ceiling and is not supported: " + dimension);
        return new SpawnEntry(id, SpawnUiConfig.component(o,"name",Component.literal(id.toString()),1024),
            SpawnUiConfig.component(o,"description",Component.empty(),4096),
            new ResourceLocation(GsonHelper.getAsString(o, "icon", "minecraft:compass")),
            dimension,
            locator, target,
            GsonHelper.getAsString(o, "required_mod", ""), bounded(o, "order", 100, -10000, 10000),
            bounded(o, "search_radius", 32, 1, 100), min, max, side,
            o.has("preview") ? new ResourceLocation(GsonHelper.getAsString(o, "preview")) : null,
            bounded(o, "candidate_count", 3, 1, 32), capacity(o),
            GsonHelper.getAsBoolean(o, "require_open_sky", true), exclusions(o));
    }
    private static int capacity(JsonObject o) {
        int value = GsonHelper.getAsInt(o, "capacity_per_instance", -1);
        if (value != -1 && (value < 1 || value > 10000)) throw new IllegalArgumentException("capacity_per_instance must be -1 or 1..10000");
        return value;
    }
    private static java.util.List<String> exclusions(JsonObject o) {
        if (!o.has("exclusions")) return java.util.List.of();
        var array = GsonHelper.getAsJsonArray(o, "exclusions");
        if (array.size() > 64) throw new IllegalArgumentException("Too many exclusions");
        java.util.List<String> result = new java.util.ArrayList<>();
        for (var value : array) {
            String pattern = limited(value.getAsString().trim(), 256);
            if (pattern.isEmpty()) continue;
            if (!pattern.contains("*") && ResourceLocation.tryParse(pattern.startsWith("#") ? pattern.substring(1) : pattern) == null)
                throw new IllegalArgumentException("Invalid exclusion: " + pattern);
            if (pattern.startsWith("#") && pattern.contains("*")) throw new IllegalArgumentException("Tag exclusions cannot contain wildcards");
            result.add(pattern);
        }
        return java.util.List.copyOf(result);
    }
    private static int bounded(JsonObject o, String k, int def, int min, int max) {
        int n = GsonHelper.getAsInt(o, k, def);
        if (n < min || n > max) throw new IllegalArgumentException(k + " outside " + min + ".." + max);
        return n;
    }
    private static String limited(String s, int max) {
        if (s.length() > max) throw new IllegalArgumentException("Text too long");
        return s;
    }
}
