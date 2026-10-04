package dev.tide.spawnselector;

/** Stable world identity; pure value type, independent of loaded Java objects. */
public record StructureInstanceId(String dimension, String structureId, int chunkX, int chunkZ) {
    public String key() { return dimension + "|" + structureId + "|" + chunkX + "|" + chunkZ; }
}
