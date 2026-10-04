package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.tags.*;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

public final class SafeSpawnFinder extends PlayerRespawnLogic implements AutoCloseable {
    private static final int MAX_TOUCHED_CHUNKS = 32;
    private static final long MAX_SEARCH_TICKS = 600L;
    private static final TicketType<UUID> TICKET = TicketType.create("spawnselector", UUID::compareTo);
    private final UUID ticketOwner = UUID.randomUUID();
    private final ResourceLocation entryId;
    private final long startedNanos = System.nanoTime();
    private final long deadlineGameTime;
    private final ServerLevel level;
    private final BoundingBox excluded;
    private final List<Perimeter.Point> candidates;
    private final SpawnEntry entry;
    private final SpawnSafetyValidator validator;
    private record Promising(Perimeter.Point point, int score) {}
    private final List<Promising> promising = new ArrayList<>();
    private boolean densePhase;
    private final float requiredWidth;
    private final float requiredHeight;
    private net.minecraft.network.chat.Component failureReason = net.minecraft.network.chat.Component.empty();
    private final Set<Long> touchedChunks = new HashSet<>();
    private final Set<Long> failedChunks = new HashSet<>();
    private boolean exhausted;
    private boolean closed;
    private int candidateCount;
    /** Candidates whose safety check has finished; used for the user-facing countdown. */
    private int checkedCount;
    private Perimeter.Point pending;
    private Set<Long> pendingChunks = Set.of();
    private final Map<ChunkPos, java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
        net.minecraft.world.level.chunk.ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> loading = new HashMap<>();
    public SafeSpawnFinder(ServerLevel level, BoundingBox bounds, SpawnEntry entry, ServerPlayer player) {
        this.level = level; this.excluded = bounds; this.entryId = entry.id();
        this.entry=entry;
        validator=new SpawnSafetyValidator(level,bounds,entry.requireOpenSky());
        deadlineGameTime = level.getGameTime() + MAX_SEARCH_TICKS;
        requiredWidth = (float)Math.max(Math.max(player.getBoundingBox().getXsize(), player.getBoundingBox().getZsize()),
            Math.max(player.getDimensions(Pose.STANDING).width, player.getBbWidth()));
        requiredHeight = (float)Math.max(player.getBoundingBox().getYsize(),
            Math.max(player.getDimensions(Pose.STANDING).height, player.getBbHeight()));
        candidates = new ArrayList<>(Perimeter.candidates(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
            entry.minDistance(), entry.maxDistance(), entry.side()).stream()
            .filter(point -> level.getWorldBorder().isWithinBounds(new BlockPos(point.x(), 64, point.z()))).toList());
        if (requiredWidth > 8 || requiredHeight > 16) {
            exhausted = true;
            failureReason = net.minecraft.network.chat.Component.translatable("spawnselector.safe.body_unsupported");
        } else if (candidates.isEmpty()) {
            exhausted = true;
            failureReason = net.minecraft.network.chat.Component.translatable("spawnselector.safe.border");
        }
    }
    public boolean exhausted() { return exhausted; }
    public net.minecraft.network.chat.Component failureReason() { return failureReason; }
    public int progress() { return candidateCount; }
    public int maximum() { return candidates.size(); }
    public int checked() { return checkedCount; }
    public int remaining() { return Math.max(0, candidates.size() - checkedCount); }
    /** Consume cheap failures in a short time slice; yield as soon as chunk I/O is pending. */
    public BlockPos step(ServerPlayer player) {
        if (exhausted) return null;
        if (level.getGameTime() >= deadlineGameTime) {
            failureReason = net.minecraft.network.chat.Component.translatable("spawnselector.safe.timeout");
            exhausted = true;
            close();
            return null;
        }
        long deadline = System.nanoTime() + 500_000L;
        int processed = 0;
        while (!exhausted && !closed && processed < 16 && System.nanoTime() < deadline) {
            if (pending != null) {
                Set<Long> failed = new HashSet<>();
                boolean waiting = false;
                for (long key : pendingChunks) {
                    var future = loading.get(new ChunkPos(key));
                    if (future == null || future.isCompletedExceptionally()) failed.add(key);
                    else if (!future.isDone()) waiting = true;
                    else {
                        try { if (future.join().left().isEmpty()) failed.add(key); }
                        catch (RuntimeException ex) { failed.add(key); }
                    }
                }
                if (!failed.isEmpty()) {
                    for (long key : failed) {
                        failedChunks.add(key);
                        ChunkPos chunk = new ChunkPos(key);
                        if (loading.remove(chunk) != null)
                            level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, ticketOwner);
                    }
                    pending = null;
                    pendingChunks = Set.of();
                    checkedCount++;
                    processed++;
                    continue;
                }
                if (waiting) return null;
                var point = pending;
                pending = null;
                pendingChunks = Set.of();
                processed++;
                BlockPos p = entry.requireOpenSky() ? getOverworldRespawnPos(level, point.x(), point.z())
                    : new BlockPos(point.x(),level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,point.x(),point.z()),point.z());
                if (p != null && recheck(player, p)) return p.immutable();
                if(!entry.requireOpenSky() && p!=null) {
                    // Bounded vertical fallback below overhangs; never scan whole chunks.
                    for(int depth=1;depth<=16 && p.getY()-depth>level.getMinBuildHeight();depth++) {
                        BlockPos lower=p.below(depth);
                        if(level.getBlockState(lower.below()).isFaceSturdy(level,lower.below(),Direction.UP)
                            && recheck(player,lower)) return lower.immutable();
                    }
                }
                if (!densePhase) {
                    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,point.x(),point.z());
                    BlockPos ground=new BlockPos(point.x(),y-1,point.z());
                    int score=level.getBlockState(ground).getFluidState().isEmpty()?0:100;
                    promising.add(new Promising(point,score));
                }
                checkedCount++;
                continue;
            }
            if (candidateCount >= candidates.size()) {
                if (!densePhase) {
                    densePhase=true;
                    var anchors=promising.stream().sorted(Comparator.comparingInt(Promising::score)).limit(8).map(Promising::point).toList();
                    candidates.addAll(Perimeter.refine(excluded.minX(),excluded.minZ(),excluded.maxX(),excluded.maxZ(),
                        entry.minDistance(),entry.maxDistance(),anchors,candidates).stream()
                        .filter(point -> level.getWorldBorder().isWithinBounds(new BlockPos(point.x(),64,point.z()))).toList());
                    SpawnSelector.LOG.info("Safe spawn {} enters dense fallback: {} promising segments, {} total candidates", entryId,anchors.size(),candidates.size());
                    if(candidateCount<candidates.size()) continue;
                }
                exhausted=true; return null;
            }
            var point = candidates.get(candidateCount++);
            int x = point.x(), z = point.z();
            int margin = (int) Math.ceil(requiredWidth / 2) + 2;
            Set<Long> needed = new HashSet<>();
            for (int cx = (x - margin) >> 4; cx <= (x + margin) >> 4; cx++)
                for (int cz = (z - margin) >> 4; cz <= (z + margin) >> 4; cz++) needed.add(ChunkPos.asLong(cx, cz));
            if (needed.stream().anyMatch(failedChunks::contains)) { checkedCount++; processed++; continue; }
            long added = needed.stream().filter(c -> !touchedChunks.contains(c)).count();
            if (touchedChunks.size() + added > MAX_TOUCHED_CHUNKS) { checkedCount++; processed++; continue; }
            for (long key : needed) {
                ChunkPos chunk = new ChunkPos(key);
                if (!loading.containsKey(chunk)) {
                    touchedChunks.add(key);
                    level.getChunkSource().addRegionTicket(TICKET, chunk, 0, ticketOwner);
                    loading.put(chunk, ChunkPreparation.request(level,chunk.x,chunk.z,ChunkStatus.FULL));
                }
            }
            pending = point;
            pendingChunks = Set.copyOf(needed);
        }
        return null;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        loading.keySet().forEach(chunk -> level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, ticketOwner));
        loading.clear(); pending = null; pendingChunks = Set.of();
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMs >= 1000L)
            SpawnSelector.LOG.info("Safe spawn search {} took {} ms; candidates={}, chunks={}",
                entryId, elapsedMs, candidateCount, touchedChunks.size());
    }
    public boolean recheck(ServerPlayer player, BlockPos p) {
        float width = (float)Math.max(Math.max(player.getBoundingBox().getXsize(),player.getBoundingBox().getZsize()),
            Math.max(requiredWidth, Math.max(player.getDimensions(Pose.STANDING).width, player.getBbWidth())));
        float height = (float)Math.max(player.getBoundingBox().getYsize(),
            Math.max(requiredHeight, Math.max(player.getDimensions(Pose.STANDING).height, player.getBbHeight())));
        if (width > 8 || height > 16) {
            failureReason = net.minecraft.network.chat.Component.translatable("spawnselector.safe.body_unsupported");
            exhausted = true;
            close();
            return false;
        }
        return validator.safe(player, p, width, height);
    }
}
