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
    /** Which side of the centreline (+1 right, -1 left, walking from village a to b), and the wall/fence there (-1 none). */
    public final byte[] side = new byte[256], run = new byte[256];
    private boolean any;
    public final java.util.List<EdgePlan.Decor> decor = new java.util.ArrayList<>();

    public ChunkMask(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        Arrays.fill(in, Float.NEGATIVE_INFINITY);
        Arrays.fill(run, (byte) -1);
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

    /** Planned surface Y rounded to half blocks. */
    public double surface(int i) {
        return Math.round(y[i] * 2) / 2.0;
    }

    public static ChunkMask build(int cx, int cz, List<EdgePlan> plans, List<VillageOracle.Footprint> villages) {
        ChunkMask m = new ChunkMask(cx, cz);
        long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        int bx = cx << 4, bz = cz << 4;
        for (EdgePlan p : plans) {
            int[] segs = p.segmentsIn(key);
            if (segs == null) continue;
            for (int i : segs) {
                float x0 = p.x[i], z0 = p.z[i], x1 = p.x[i + 1], z1 = p.z[i + 1];
                float r = Math.max(p.hw[i], p.hw[i + 1]) + EdgePlan.REACH;
                int lx0 = Math.max(0, (int) Math.floor(Math.min(x0, x1) - r) - bx), lx1 = Math.min(15, (int) Math.ceil(Math.max(x0, x1) + r) - bx);
                int lz0 = Math.max(0, (int) Math.floor(Math.min(z0, z1) - r) - bz), lz1 = Math.min(15, (int) Math.ceil(Math.max(z0, z1) + r) - bz);
                float dx = x1 - x0, dz = z1 - z0, len2 = dx * dx + dz * dz;
                for (int lx = lx0; lx <= lx1; lx++) {
                    for (int lz = lz0; lz <= lz1; lz++) {
                        float px = bx + lx + 0.5f, pz = bz + lz + 0.5f;
                        float t = len2 == 0 ? 0 : Math.clamp(((px - x0) * dx + (pz - z0) * dz) / len2, 0f, 1f);
                        float qx = x0 + t * dx - px, qz = z0 + t * dz - pz;
                        float dist = (float) Math.sqrt(qx * qx + qz * qz);
                        float hw = p.hw[i] + t * (p.hw[i + 1] - p.hw[i]);
                        float v = hw - dist;
                        int c = idx(lx, lz);
                        if (v > m.in[c]) {
                            m.in[c] = v;
                            m.y[c] = p.y[i] + t * (p.y[i + 1] - p.y[i]);
                            int k = t < 0.5f ? i : i + 1;
                            m.kind[c] = p.kind[k];
                            m.runLen[c] = p.runLen[k];
                            m.runPos[c] = p.runPos[k];
                            m.wood[c] = p.wood[k];
                            float cross = dx * (pz - z0) - dz * (px - x0);
                            m.side[c] = (byte) (cross > 0 ? 1 : -1);
                            m.run[c] = -1;
                            for (EdgePlan.Run run : p.runs)
                                if (i >= run.s0() && i <= run.s1() && (run.side() == 0 || run.side() == m.side[c])) m.run[c] = run.type();
                            m.s[c] = i + t;
                            m.any = true;
                        }
                    }
                }
            }
        }
        for (EdgePlan p : plans) {
            for (EdgePlan.Decor d : p.decor) {
                if (d.chunk() != key) continue;
                boolean inVillage = false;
                for (VillageOracle.Footprint f : villages) inVillage |= f.contains(d.x(), d.z(), 1);
                if (!inVillage) {
                    m.decor.add(d);
                    m.any = true;
                }
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
                    for (int lz = 0; lz < 16; lz++)
                        if (f.contains(bx + lx, bz + lz, 1)) m.in[idx(lx, lz)] = Float.NEGATIVE_INFINITY;
        }
        return m;
    }
}
