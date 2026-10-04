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

/** Collision, support and hazard policy, separated from search/chunk scheduling. */
public final class SpawnSafetyValidator {
    private static final TagKey<Block> UNSAFE = TagKey.create(Registries.BLOCK,
        new ResourceLocation(SpawnSelector.ID, "unsafe_spawn"));
    private final ServerLevel level;
    private final BoundingBox excluded;
    private final boolean requireOpenSky;
    SpawnSafetyValidator(ServerLevel level,BoundingBox excluded,boolean requireOpenSky) {
        this.level=level; this.excluded=excluded; this.requireOpenSky=requireOpenSky;
    }
    boolean safe(ServerPlayer player, BlockPos p, float width, float height) {
        if (p.getY() <= level.getMinBuildHeight() || p.getY() + height + 1 >= level.getMaxBuildHeight()) return false;
        AABB body = new AABB(p.getX() + .5 - width / 2, p.getY(), p.getZ() + .5 - width / 2,
            p.getX() + .5 + width / 2, p.getY() + height, p.getZ() + .5 + width / 2);
        AABB actual = player.getBoundingBox().move(p.getX() + .5 - player.getX(),
            p.getY() - player.getY(), p.getZ() + .5 - player.getZ());
        body = body.minmax(actual);
        // Do not let a size change or unusual asymmetric modded box implicitly
        // generate unticketed FULL chunks while validating the footprint.
        int pad = Math.max(1, (int)Math.ceil(width / 2)) + 2;
        double left=Math.min(body.minX-.05,p.getX()-pad), right=Math.max(body.maxX+.05,p.getX()+pad);
        double top=Math.min(body.minZ-.05,p.getZ()-pad), bottom=Math.max(body.maxZ+.05,p.getZ()+pad);
        for(int cx=((int)Math.floor(left))>>4;cx<=((int)Math.floor(right))>>4;cx++)
            for(int cz=((int)Math.floor(top))>>4;cz<=((int)Math.floor(bottom))>>4;cz++)
                if(level.getChunkSource().getChunkNow(cx,cz)==null) return false;
        if (intersectsXZ(body, excluded) || !level.getWorldBorder().isWithinBounds(body)
            || !level.noCollision(player, body.inflate(.05, 0, .05).expandTowards(0, .05, 0)) || level.containsAnyLiquid(body)
            || (requireOpenSky && !level.canSeeSky(p))) return false;
        // The selected structure is excluded by its whole X/Z footprint above. Other
        // structures only matter when their actual 3D bounds intersect the player.
        for (int cx = ((int)Math.floor(body.minX)) >> 4; cx <= ((int)Math.floor(body.maxX)) >> 4; cx++)
            for (int cz = ((int)Math.floor(body.minZ)) >> 4; cz <= ((int)Math.floor(body.maxZ)) >> 4; cz++)
                for (var start : level.structureManager().startsForStructure(new ChunkPos(cx, cz), s -> true))
                    if (start.isValid() && intersects3D(body, start.getBoundingBox())) return false;
        // A 3x3 landing pad, plus the full footprint for unusually wide players.
        int footprint = Math.max(1, (int)Math.ceil(width / 2));
        for (int dx = -footprint; dx <= footprint; dx++) for (int dz = -footprint; dz <= footprint; dz++) {
            int surface = supportSurface(p, p.getX()+dx, p.getZ()+dz);
            if (Math.abs(surface - p.getY()) > 1) return false;
            BlockPos ground = new BlockPos(p.getX()+dx, surface-1, p.getZ()+dz);
            var state = level.getBlockState(ground);
            if (!state.isFaceSturdy(level, ground, Direction.UP) || state.is(BlockTags.LEAVES) || hazardous(ground)) return false;
            for (int y = Math.min(surface, p.getY()) - 1; y <= p.getY() + Math.ceil(height); y++)
                if (hazardous(new BlockPos(ground.getX(), y, ground.getZ()))) return false;
        }
        // The actual feet must have full support, not a fall down to the neighboring slope.
        return level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }
    private boolean hazardous(BlockPos p) {
        var s = level.getBlockState(p);
        return s.is(UNSAFE) || !s.getFluidState().isEmpty();
    }
    private int supportSurface(BlockPos feet,int x,int z) {
        if(!requireOpenSky) {
            // An overhead canopy must not become the "ground" in the local slope check.
            for(int y=feet.getY();y>=feet.getY()-2;y--) {
                BlockPos ground=new BlockPos(x,y,z);
                if(level.getBlockState(ground).isFaceSturdy(level,ground,Direction.UP)) return y+1;
            }
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
    }
    private static boolean intersectsXZ(AABB a, BoundingBox b) {
        return a.maxX > b.minX() && a.minX < b.maxX()+1 && a.maxZ > b.minZ() && a.minZ < b.maxZ()+1;
    }
    private static boolean intersects3D(AABB a, BoundingBox b) {
        return intersectsXZ(a, b) && a.maxY > b.minY() && a.minY < b.maxY()+1;
    }
}
