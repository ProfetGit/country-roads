package io.github.profetgit.countryroads.plan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * One road between two villages: a centreline sampled every block (x, z), its planned surface Y (blocks, before
 * half-step rounding), half-width and segment kind, plus an index from chunk to the segments that can touch it.
 */
public final class EdgePlan {
    public static final byte GROUND = 0, CAUSEWAY = 1, BRIDGE = 2, BOARDWALK = 3, TUNNEL = 4;
    /** Columns this far outside the road edge still carry mask data (guards, tree clearing). */
    public static final float REACH = 5f;

    public final Edge edge;
    public final float[] x, z, y, hw;
    public final byte[] kind;
    /** Length of the run of equal kind this sample is in, and distance (samples) to the nearer end of that run. */
    public final short[] runLen, runPos;
    /** Wood style for decks: 0 spruce, 1 oak, 2 mangrove, 3 dark oak. */
    public final byte[] wood;
    public final long routeNanos;
    /** Signposts and lamp posts beside the road, at exact columns (Y found at paint time). */
    public final java.util.List<Decor> decor = new java.util.ArrayList<>();
    /** A wayside camp: its start chunk (0 when none), structure id and piece boxes the road keeps clear of. */
    public long campChunk;
    public net.minecraft.resources.Identifier camp;
    public java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> campBoxes = java.util.List.of();
    public String nameA = "", nameB = "";

    public static final byte SIGN = 0, LAMP = 1;

    /** type SIGN: rot = standing sign rotation (0-15), front/back text; LAMP: rot unused. */
    public record Decor(int x, int z, float y, byte type, byte rot, String front1, String front2, String back) {
        public long chunk() {
            return ChunkPos.pack(x >> 4, z >> 4);
        }
    }
    private final Long2ObjectOpenHashMap<int[]> bins = new Long2ObjectOpenHashMap<>();

    public EdgePlan(Edge edge, float[] x, float[] z, float[] y, float[] hw, byte[] kind, byte[] wood, long routeNanos) {
        this.edge = edge;
        this.x = x;
        this.z = z;
        this.y = y;
        this.hw = hw;
        this.kind = kind;
        this.wood = wood;
        this.routeNanos = routeNanos;
        int n = x.length;
        this.runLen = new short[n];
        this.runPos = new short[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            for (int k = i; k <= j; k++) {
                runLen[k] = (short) Math.min(Short.MAX_VALUE, j - i + 1);
                runPos[k] = (short) Math.min(k - i, j - k);
            }
            i = j + 1;
        }
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
