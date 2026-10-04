package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Shares discovery jobs across players; player lifecycle remains in SelectionService. */
public final class SpawnSelectorManager {
    private static final Map<MinecraftServer, SpawnSelectorManager> managers = new WeakHashMap<>();
    private final Map<SpawnEntry, MultiStructureLocator> discovery = new LinkedHashMap<>();
    static SpawnSelectorManager get(MinecraftServer server) { return managers.computeIfAbsent(server, s -> new SpawnSelectorManager()); }
    boolean discover(MinecraftServer server, SpawnEntry entry) {
        if (!server.isSameThread()) throw new IllegalStateException("Discovery commit requires server thread");
        var pool = PlayerSelections.get(server).pool;
        if (pool.eligible(server,entry).size() >= entry.candidateCount() || pool.discoveryComplete(server,entry)) return true;
        var level = Locators.level(server, entry);
        if (level == null) return true;
        discovery.entrySet().removeIf(old -> {
            if(old.getKey().id().equals(entry.id()) && !old.getKey().equals(entry)) { old.getValue().close(); return true; }
            return false;
        });
        MultiStructureLocator job = discovery.computeIfAbsent(entry, e -> new MultiStructureLocator(level, e, level.getSharedSpawnPos()));
        if (!job.done()) return false;
        pool.discover(server, entry, job.results());
        if(job.cacheable()) pool.finishDiscovery(server,entry);
        return true;
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var manager = managers.get(event.getServer());
        if (manager == null) return;
        int budget = 4;
        // Rotate to prevent many targets starving each other's discovery.
        var active = manager.discovery.entrySet().stream().filter(e -> !e.getValue().done()).toList();
        if (active.isEmpty()) return;
        int offset = Math.floorMod(event.getServer().getTickCount(), active.size());
        for (int i = 0; i < active.size() && budget-- > 0; i++) {
            var job = active.get((i + offset) % active.size());
            try { job.getValue().step(); }
            catch (RuntimeException | LinkageError ex) {
                SpawnSelector.LOG.error("Discovery failed for {}", job.getKey().id(), ex);
                job.getValue().fail();
            }
        }
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        var manager = managers.remove(event.getServer());
        if (manager != null) manager.discovery.values().forEach(MultiStructureLocator::close);
    }
}
