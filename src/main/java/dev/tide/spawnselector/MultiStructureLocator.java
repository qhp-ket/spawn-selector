package dev.tide.spawnselector;

import com.mojang.datafixers.util.Either;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;

/** Bounded discovery of actual starts, never FULL terrain preparation. Server-thread only. */
final class MultiStructureLocator implements AutoCloseable {
    private static final int MAX_PLANNED = 8192, MAX_START_CHUNKS = 512;
    private static final int TICKET_RADIUS = 33 - ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);
    private static final TicketType<UUID> TICKET = TicketType.create("spawnselector_discovery", UUID::compareTo);
    private record Probe(ChunkPos chunk, Set<Holder<Structure>> targets) {}
    private final ServerLevel level;
    private final SpawnEntry entry;
    private final BlockPos near;
    private final UUID owner = UUID.randomUUID();
    private final List<Probe> probes;
    private final List<Holder<Structure>> unknown = new ArrayList<>();
    private final Set<StructureInstanceId> found = new LinkedHashSet<>();
    private final long deadline;
    private int index;
    private Probe pending;
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
    private boolean done, fallbackTried, closed;
    private boolean cacheable=true;

    MultiStructureLocator(ServerLevel level, SpawnEntry entry, BlockPos near) {
        this.level = level; this.entry = entry; this.near = near;
        deadline = level.getGameTime() + 1200;
        var state = level.getChunkSource().getGeneratorState();
        Map<StructurePlacement, Set<Holder<Structure>>> placements = new LinkedHashMap<>();
        for (var holder : StructureTargetResolver.resolve(level, entry)) {
            var holderPlacements=state.getPlacementsForStructure(holder);
            if(holderPlacements.isEmpty()) unknown.add(holder);
            for (var placement : holderPlacements)
                placements.computeIfAbsent(placement, p -> new LinkedHashSet<>()).add(holder);
        }
        Map<ChunkPos, Set<Holder<Structure>>> plan = new HashMap<>();
        int perPlacement = Math.max(1, MAX_PLANNED / Math.max(1, placements.size()));
        int remaining = MAX_PLANNED;
        groups: for (var group : placements.entrySet()) {
            if(remaining==0) break;
            var placement = group.getKey();
            if (placement instanceof RandomSpreadStructurePlacement random) {
                int spacing = random.spacing();
                int centerX = Math.floorDiv(near.getX() >> 4, spacing);
                int centerZ = Math.floorDiv(near.getZ() >> 4, spacing);
                int enumerated = 0;
                outer: for (int ring = 0; ring <= entry.searchRadius(); ring++) {
                    for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        if (++enumerated > perPlacement) break outer;
                        if(remaining--==0) break groups;
                        ChunkPos chunk = random.getPotentialStructureChunk(state.getLevelSeed(),
                            (centerX + dx) * spacing, (centerZ + dz) * spacing);
                        if (placement.isStructureChunk(state, chunk.x, chunk.z))
                            add(plan, chunk, group.getValue());
                    }
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                var positions = state.getRingPositionsFor(rings);
                if (positions == null) unknown.addAll(group.getValue());
                else {
                    var nearby=positions.stream().sorted(Comparator.comparingDouble(this::distance))
                        .limit(Math.min(perPlacement,remaining)).toList();
                    remaining-=nearby.size();
                    nearby.forEach(chunk -> add(plan, chunk, group.getValue()));
                }
            } else unknown.addAll(group.getValue());
        }
        probes = plan.entrySet().stream().map(e -> new Probe(e.getKey(), Set.copyOf(e.getValue())))
            .sorted(Comparator.comparingDouble((Probe p) -> distance(p.chunk()))
                .thenComparingInt(p -> p.chunk().x).thenComparingInt(p -> p.chunk().z))
            .limit(MAX_START_CHUNKS).toList();
    }
    private void add(Map<ChunkPos, Set<Holder<Structure>>> plan, ChunkPos pos, Set<Holder<Structure>> holders) {
        if (level.getWorldBorder().isWithinBounds(pos.getMiddleBlockPosition(64)))
            plan.computeIfAbsent(pos, p -> new LinkedHashSet<>()).addAll(holders);
    }
    private double distance(ChunkPos pos) {
        double dx = pos.getMiddleBlockX() - near.getX(), dz = pos.getMiddleBlockZ() - near.getZ();
        return dx * dx + dz * dz;
    }
    boolean done() { return done; }
    void fail() { cacheable=false; finish(); }
    boolean cacheable() { return done && cacheable; }
    List<StructureInstanceId> results() { return List.copyOf(found); }
    void step() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Discovery requires server thread");
        if (done || closed) return;
        if (level.getGameTime() >= deadline) { cacheable=false; finish(); return; }
        if (pending != null) {
            if (!future.isDone()) return;
            try {
                var loaded=future.join().left();
                if(loaded.isEmpty())cacheable=false;
                loaded.ifPresent(chunk -> {
                    for (var holder : pending.targets()) {
                        var start = chunk.getStartForStructure(holder.value());
                        if (start != null && start.isValid()) found.add(StructureInstanceLocator.identity(level, holder, start));
                        if (found.size() >= entry.candidateCount()) break;
                    }
                });
            } catch (RuntimeException | LinkageError ex) {
                cacheable=false;
                SpawnSelector.LOG.debug("Start discovery failed at {}", pending.chunk(), ex);
            } finally { releasePending(); }
        }
        if (found.size() >= entry.candidateCount()) { finish(); return; }
        if (index < probes.size()) {
            pending = probes.get(index++);
            try {
                level.getChunkSource().addRegionTicket(TICKET, pending.chunk(), TICKET_RADIUS, owner);
                future = ChunkPreparation.request(level, pending.chunk().x, pending.chunk().z, ChunkStatus.STRUCTURE_STARTS);
            } catch (RuntimeException | LinkageError ex) { releasePending(); throw ex; }
            return;
        }
        if (!fallbackTried && !unknown.isEmpty()) {
            fallbackTried = true;
            SpawnSelector.LOG.warn("Unknown placement for {}; using vanilla single-instance fallback", entry.id());
            var id = StructureInstanceLocator.nearest(level, HolderSet.direct(unknown.stream().distinct().toList()), near, entry.searchRadius());
            if (id != null) found.add(id);
        }
        finish();
    }
    private void finish() {
        done = true;
        SpawnSelector.LOG.info("Discovery {}: {} instances, {}/{} start chunks, FULL requests=0",
            entry.id(), found.size(), index, probes.size());
        close();
    }
    private void releasePending() {
        if (pending != null) level.getChunkSource().removeRegionTicket(TICKET, pending.chunk(), TICKET_RADIUS, owner);
        pending = null; future = null;
    }
    @Override public void close() { releasePending(); closed = true; }
}
