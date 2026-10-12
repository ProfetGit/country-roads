package io.github.profetgit.countryroads.gen;

import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Per-thread state while a chunk runs its FEATURES step: the road masks of the chunk and its neighbours, whether a
 * feature is being placed (depth) and whether our own painter is writing (bypass).
 */
public final class GenContext {
    private static final ThreadLocal<GenContext> CURRENT = new ThreadLocal<>();

    private final RoadNetwork network;
    private final Long2ObjectOpenHashMap<ChunkMask> masks = new Long2ObjectOpenHashMap<>();
    int featureDepth;
    private boolean bypass;

    private GenContext(RoadNetwork network) {
        this.network = network;
    }

    public static void begin(ServerLevel level, ChunkPos center) {
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            CURRENT.remove();
            return;
        }
        GenContext c = new GenContext(n);
        boolean any = false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                ChunkMask m = n.mask(center.x() + dx, center.z() + dz);
                c.masks.put(ChunkPos.pack(center.x() + dx, center.z() + dz), m);
                any |= m.any();
            }
        if (any) CURRENT.set(c);
        else CURRENT.remove();
    }

    public static void end() {
        CURRENT.remove();
    }

    public static ChunkMask mask(ChunkPos pos) {
        GenContext c = CURRENT.get();
        return c == null ? null : c.masks.get(pos.pack());
    }

    public static void enterFeature() {
        GenContext c = CURRENT.get();
        if (c != null) c.featureDepth++;
    }

    public static void exitFeature() {
        GenContext c = CURRENT.get();
        if (c != null && c.featureDepth > 0) c.featureDepth--;
    }

    public static void bypass(boolean on) {
        GenContext c = CURRENT.get();
        if (c != null) c.bypass = on;
    }

    private ChunkMask maskAt(int x, int z) {
        return masks.get(ChunkPos.pack(x >> 4, z >> 4));
    }

    /** A feature write into the road's air space (or its surface) is dropped. */
    public static boolean blocksFeatureWrite(BlockPos pos) {
        GenContext c = CURRENT.get();
        if (c == null || c.bypass || c.featureDepth == 0) return false;
        ChunkMask m = c.maskAt(pos.getX(), pos.getZ());
        if (m == null || !m.any()) return false;
        int i = ChunkMask.idx(pos.getX() & 15, pos.getZ() & 15);
        if (m.in[i] < -0.6f) return false;
        int top = (int) Math.floor(m.surface(i));
        return pos.getY() >= top && pos.getY() <= top + 5;
    }

    /** Big features (trees, boulders, lakes, mushrooms) whose origin is this close to the road are not placed. */
    public static boolean blocksBigFeature(BlockPos origin, int radius) {
        GenContext c = CURRENT.get();
        if (c == null || c.bypass) return false;
        for (int dx = -radius; dx <= radius; dx += Math.max(1, radius)) {
            for (int dz = -radius; dz <= radius; dz += Math.max(1, radius)) {
                int x = origin.getX() + dx, z = origin.getZ() + dz;
                ChunkMask m = c.maskAt(x, z);
                if (m == null || !m.any()) continue;
                if (m.in[ChunkMask.idx(x & 15, z & 15)] >= -EdgePlan.REACH) return true;
            }
        }
        return false;
    }
}
