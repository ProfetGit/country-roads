package io.github.profetgit.countryroads.plan;

import java.util.Arrays;
import java.util.List;
import io.github.profetgit.countryroads.world.VillageOracle;

/**
 * Per-column road data of one chunk. in = half-width minus distance to the centreline (>= 0 on the road, negative
 * outside), y = planned surface (half-steps), kind = segment kind. Columns without road data have in = -inf.
 */
public final class ChunkMask {
    public static final ChunkMask EMPTY = new ChunkMask(0, 0);

    public final int cx, cz;
    public final float[] in = new float[256];
    public final float[] y = new float[256];
    public final byte[] kind = new byte[256];
    public final float[] s = new float[256];
    public final short[] runLen = new short[256], runPos = new short[256];
    public final byte[] wood = new byte[256];
    /** Bridge crossing data (EdgePlan.bridge*), the column's place in its run (0 at the first edge, runLen at the last) and the road's direction. */
    public final byte[] bridge = new byte[256], bridgeWood = new byte[256], bridgeRise = new byte[256];
    public final float[] runU = new float[256], tx = new float[256], tz = new float[256];
    /** Unit direction from the centreline out to the column (0 on the line). */
    public final float[] ox = new float[256], oz = new float[256];
    /** Samples per block along the road here (more than 1 where a straightened line packs its samples closer). */
    public final float[] ds = new float[256];
    /** The column's nearest road point is a joint between two samples (it lies on the outer side of a bend): its runU is clamped there. */
    public final boolean[] joint = new boolean[256];
    public final byte[] earth = new byte[256];
    /** Which side of the centreline (+1 right, -1 left, walking from village a to b), and the wall/fence there (-1 none). */
    public final byte[] side = new byte[256], run = new byte[256];
    /** |nx| + |nz| of the unit direction from the centreline to the column (1 when the road runs along an axis, 1.41 diagonally). */
    public final float[] slant = new float[256];
    private boolean any;
    public final java.util.List<EdgePlan.Decor> decor = new java.util.ArrayList<>();

    public ChunkMask(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        Arrays.fill(in, Float.NEGATIVE_INFINITY);
        Arrays.fill(run, (byte) -1);
        Arrays.fill(connD, 99f);
    }

    public boolean any() {
        return any;
    }

    public static int idx(int lx, int lz) {
        return (lz << 4) | lx;
    }

    public float in(int lx, int lz) {
        return in[idx(lx, lz)];
    }

    /** Columns on or just beside a connector (road inside a village, from its street out to open ground). */
    public final boolean[] conn = new boolean[256];
    /** Samples between a connector column and its dock (the street end); 99 where the nearest segment is not a connector. */
    public final float[] connD = new float[256];

    /** Whether the column carries a half step (a slab): only the row just below a one-block rise. */
    public final boolean[] half = new boolean[256];

    /** Planned surface: whole blocks, plus a half step on the single row below each rise. */
    public double surface(int i) {
        return Math.round(y[i]) + (half[i] ? 0.5 : 0);
    }

    private static float yAt(EdgePlan p, float s) {
        int n = p.length();
        if (s <= 0) return p.y[0];
        if (s >= n - 1) return p.y[n - 1];
        int i = (int) s;
        float t = s - i;
        return p.y[i] + t * (p.y[i + 1] - p.y[i]);
    }

    /**
     * Posts from different roads can land on top of each other (two roads leaving one village). Signposts within 4
     * blocks merge into one post whose sign lists both destinations; lamp posts near any other post are dropped. The
     * survivor is chosen by position, so every chunk agrees.
     */
    public static java.util.List<EdgePlan.Decor> resolveDecor(List<EdgePlan> plans) {
        java.util.List<EdgePlan.Decor> all = new java.util.ArrayList<>();
        for (EdgePlan p : plans) all.addAll(p.decor);
        all.sort(java.util.Comparator.comparingInt((EdgePlan.Decor d) -> d.type()).thenComparingInt(EdgePlan.Decor::x).thenComparingInt(EdgePlan.Decor::z)
            .thenComparing(EdgePlan.Decor::front1));
        java.util.List<EdgePlan.Decor> out = new java.util.ArrayList<>();
        for (EdgePlan.Decor d : all) {
            int hit = -1;
            for (int k = 0; k < out.size() && hit < 0; k++) {
                EdgePlan.Decor o = out.get(k);
                if (Math.max(Math.abs(o.x() - d.x()), Math.abs(o.z() - d.z())) <= (d.type() == EdgePlan.ORCHARD || o.type() == EdgePlan.ORCHARD ? 3 : 4)) hit = k;
            }
            if (hit < 0) out.add(d);
            else if (d.type() == EdgePlan.SIGN && out.get(hit).type() == EdgePlan.SIGN && !out.get(hit).front1().contains(d.front1())) {
                EdgePlan.Decor o = out.get(hit);
                out.set(hit, new EdgePlan.Decor(o.x(), o.z(), o.y(), o.type(), o.toRoad(), o.along(),
                    o.front1() + "\n" + o.front2(), d.front1() + "\n" + d.front2(), o.back()));
            }
        }
        return out;
    }

    public static ChunkMask build(int cx, int cz, List<EdgePlan> plans, List<VillageOracle.Footprint> villages, List<EdgePlan> near) {
        ChunkMask m = new ChunkMask(cx, cz);
        long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        int bx = cx << 4, bz = cz << 4;
        for (EdgePlan p : plans) {
            int[] segs = p.segmentsIn(key);
            if (segs == null) continue;
            for (int i : segs) {
                // a stretch shared with an earlier road is that road's to paint: two plans of one crossing tied column by
                // column, so the posts on its two rails came from different bridges and did not line up
                if (p.shared != null && p.shared[i] && p.shared[i + 1]) continue;
                float x0 = p.x[i], z0 = p.z[i], x1 = p.x[i + 1], z1 = p.z[i + 1];
                float r = Math.max(p.hw[i], p.hw[i + 1]) + EdgePlan.REACH;
                int lx0 = Math.max(0, (int) Math.floor(Math.min(x0, x1) - r) - bx), lx1 = Math.min(15, (int) Math.ceil(Math.max(x0, x1) + r) - bx);
                int lz0 = Math.max(0, (int) Math.floor(Math.min(z0, z1) - r) - bz), lz1 = Math.min(15, (int) Math.ceil(Math.max(z0, z1) + r) - bz);
                float dx = x1 - x0, dz = z1 - z0, len2 = dx * dx + dz * dz;
                for (int lx = lx0; lx <= lx1; lx++) {
                    for (int lz = lz0; lz <= lz1; lz++) {
                        float px = bx + lx + 0.5f, pz = bz + lz + 0.5f;
                        float raw = len2 == 0 ? 0 : ((px - x0) * dx + (pz - z0) * dz) / len2, t = Math.clamp(raw, 0f, 1f);
                        float qx = x0 + t * dx - px, qz = z0 + t * dz - pz;
                        float dist = (float) Math.sqrt(qx * qx + qz * qz);
                        float hw = p.hw[i] + t * (p.hw[i + 1] - p.hw[i]);
                        float v = hw - dist;
                        int c = idx(lx, lz);
                        if (v > -1.5f && (i < p.connA || i + 1 >= p.length() - p.connB)) m.conn[c] = true;
                        if (v > m.in[c]) {
                            m.in[c] = v;
                            m.connD[c] = i < p.connA ? i + t : i + 1 >= p.length() - p.connB ? p.length() - 1 - (i + t) : 99f;
                            m.y[c] = p.y[i] + t * (p.y[i + 1] - p.y[i]);
                            int here = Math.round(m.y[c]);
                            m.half[c] = Math.max(Math.round(yAt(p, i + t + 1)), Math.round(yAt(p, i + t - 1))) > here;
                            int k = t < 0.5f ? i : i + 1;
                            m.kind[c] = p.kind[k];
                            m.runLen[c] = p.runLen[k];
                            m.runPos[c] = p.runPos[k];
                            m.wood[c] = p.wood[k];
                            m.runU[c] = i + t - (k - p.runAt[k]) + 0.5f;
                            if (p.bridge != null) {
                                m.bridge[c] = p.bridge[k];
                                m.bridgeWood[c] = p.bridgeWood[k];
                                m.bridgeRise[c] = p.bridgeRise[k];
                            }
                            float sl = (float) Math.sqrt(len2);
                            m.tx[c] = sl > 0 ? dx / sl : 1;
                            m.tz[c] = sl > 0 ? dz / sl : 0;
                            m.ds[c] = sl > 1e-3f ? 1f / sl : 1f;
                            m.joint[c] = raw < 0 || raw > 1;
                            m.earth[c] = p.earth == null ? 99 : p.earth[k];
                            float cross = dx * (pz - z0) - dz * (px - x0);
                            m.side[c] = (byte) (cross > 0 ? 1 : -1);
                            m.run[c] = -1;
                            for (EdgePlan.Run run : p.runs)
                                if (i >= run.s0() && i <= run.s1() && (run.side() == 0 || run.side() == m.side[c])) m.run[c] = run.type();
                            m.s[c] = i + t;
                            m.slant[c] = dist > 1e-4f ? (Math.abs(qx) + Math.abs(qz)) / dist : 1f;
                            m.ox[c] = dist > 1e-4f ? -qx / dist : 0;
                            m.oz[c] = dist > 1e-4f ? -qz / dist : 0;
                            m.any = true;
                        }
                    }
                }
            }
        }
        for (EdgePlan.Decor d : resolveDecor(near)) {
            if (d.chunk() != key) continue;
            boolean inVillage = false;
            for (VillageOracle.Footprint f : villages) inVillage |= f.contains(d.x(), d.z(), 1);
            if (!inVillage) {
                m.decor.add(d);
                m.any = true;
            }
        }
        if (m.any) {
            for (EdgePlan p : plans)
                for (net.minecraft.world.level.levelgen.structure.BoundingBox b : p.campBoxes)
                    for (int lx = 0; lx < 16; lx++)
                        for (int lz = 0; lz < 16; lz++)
                            if (bx + lx >= b.minX() - 2 && bx + lx <= b.maxX() + 2 && bz + lz >= b.minZ() - 2 && bz + lz <= b.maxZ() + 2)
                                m.in[idx(lx, lz)] = Float.NEGATIVE_INFINITY;
            for (VillageOracle.Footprint f : villages)
                for (int lx = 0; lx < 16; lx++)
                    for (int lz = 0; lz < 16; lz++) {
                        int x = bx + lx, z = bz + lz, c = idx(lx, lz);
                        if (!f.contains(x, z, 1)) continue;
                        // a connector keeps its road through the village's ring, but never over a house or the street itself
                        boolean keep = m.conn[c] && !f.inOther(x, z, 0) && !f.bands().contains(x, z);
                        if (!keep) m.in[c] = Float.NEGATIVE_INFINITY;
                    }
        }
        return m;
    }
}
