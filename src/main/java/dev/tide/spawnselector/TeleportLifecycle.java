package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;

/** Destination tickets stay alive until the matching client chunk acknowledgement. */
final class TeleportLifecycle implements AutoCloseable {
    private static final TicketType<UUID> TICKET = TicketType.create("spawnselector_reveal", UUID::compareTo);
    private final UUID owner = UUID.randomUUID();
    private final ServerLevel level;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private boolean closed;
    TeleportLifecycle(ServerPlayer player) {
        level = player.serverLevel();
        var body = player.getBoundingBox().inflate(2, 0, 2);
        for (int x = ((int)Math.floor(body.minX)) >> 4; x <= ((int)Math.floor(body.maxX)) >> 4; x++)
            for (int z = ((int)Math.floor(body.minZ)) >> 4; z <= ((int)Math.floor(body.maxZ)) >> 4; z++) {
                ChunkPos chunk = new ChunkPos(x, z); chunks.add(chunk);
                level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner);
            }
    }
    @Override public void close() {
        if (closed) return;
        chunks.forEach(c -> level.getChunkSource().removeRegionTicket(TICKET, c, 0, owner));
        closed = true;
    }
}
