package dev.tide.spawnselector;

import java.util.*;

/** Capacity policy. The owning SavedData serializes all calls on the server thread. */
public final class ClaimLedger {
    private final Map<UUID, Boolean> claims = new LinkedHashMap<>();
    public boolean claim(UUID player, int capacity) {
        if (claims.containsKey(player)) return true;
        if (capacity != -1 && claims.size() >= capacity) return false;
        claims.put(player, false);
        return true;
    }
    public boolean hasRoom(int capacity) { return capacity == -1 || claims.size() < capacity; }
    public void complete(UUID player) {
        if (!claims.containsKey(player)) throw new IllegalStateException("Cannot complete an unclaimed instance");
        claims.put(player, true);
    }
    public void release(UUID player) { if (Boolean.FALSE.equals(claims.get(player))) claims.remove(player); }
    public void recover() { claims.entrySet().removeIf(e -> !e.getValue()); }
    public int size() { return claims.size(); }
    public boolean completed(UUID player) { return Boolean.TRUE.equals(claims.get(player)); }
    public Map<UUID, Boolean> snapshot() { return Map.copyOf(claims); }
    public void restore(UUID player, boolean completed) { claims.put(player, completed); }
}
