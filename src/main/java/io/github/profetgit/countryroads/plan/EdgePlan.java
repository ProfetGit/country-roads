package io.github.profetgit.countryroads.plan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * One road between two villages: a centreline sampled every block (x, z), its planned surface Y (blocks, before
 * half-step rounding), half-width and segment kind, plus an index from chunk to the segments that can touch it.
 */
public final class EdgePlan {
    public static final byte GROUND = 0, CAUSEWAY = 1;
    /** Columns this far outside the road edge still carry mask data (guards, tree clearing). */
    public static final float REACH = 4.5f;

    public final Edge edge;
    public final float[] x, z, y, hw;
    public final byte[] kind;
    public final long routeNanos;
    private final Long2ObjectOpenHashMap<int[]> bins = new Long2ObjectOpenHashMap<>();

    public EdgePlan(Edge edge, float[] x, float[] z, float[] y, float[] hw, byte[] kind, long routeNanos) {
        this.edge = edge;
        this.x = x;
        this.z = z;
        this.y = y;
        this.hw = hw;
        this.kind = kind;
        this.routeNanos = routeNanos;
        Long2ObjectOpenHashMap<IntArrayList> tmp = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i + 1 < x.length; i++) {
            float r = Math.max(hw[i], hw[i + 1]) + REACH + 1;
            int cx0 = (int) Math.floor((Math.min(x[i], x[i + 1]) - r)) >> 4, cx1 = (int) Math.floor((Math.max(x[i], x[i + 1]) + r)) >> 4;
            int cz0 = (int) Math.floor((Math.min(z[i], z[i + 1]) - r)) >> 4, cz1 = (int) Math.floor((Math.max(z[i], z[i + 1]) + r)) >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++)
                    tmp.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new IntArrayList()).add(i);
        }
        tmp.forEach((k, v) -> bins.put((long) k, v.toIntArray()));
    }

    public int length() {
        return x.length;
    }

    public int[] segmentsIn(long chunk) {
        return bins.get(chunk);
    }

    public boolean touches(long chunk) {
        return bins.containsKey(chunk);
    }
}
