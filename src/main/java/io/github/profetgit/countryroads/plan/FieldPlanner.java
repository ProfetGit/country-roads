package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.world.TerrainSampler;
import net.minecraft.world.level.biome.Biomes;

/**
 * Wheat fields beside plains roads: a candidate centre every 60 road samples on a gentle, dry, all-ground stretch of
 * plains, not within 60 blocks of a village end, one field per 500 blocks of road at most (chance 0.6 each).
 * -Dcountryroads.fieldDensity=F multiplies the chance and divides the spacings (testing).
 */
final class FieldPlanner {
    private static final double SPACING = 500 / Field.DENSITY;
    private static final int STEP = 60;

    private FieldPlanner() {}

    static void plan(EdgePlan p, TerrainSampler terrain) {
        int n = p.length();
        int lo = p.connA + 60, hi = n - 1 - p.connB - 60;
        if (hi - lo < 10) return;
        long seed = Field.mix(p.edge.seed() ^ 0x77686561_74L);
        double[] arc = new double[n];
        for (int i = 1; i < n; i++) arc[i] = arc[i - 1] + Math.hypot(p.x[i] - p.x[i - 1], p.z[i] - p.z[i - 1]);
        double last = -1e9;
        for (int k = 0, i = lo; i <= hi; k++, i += STEP) {
            if (arc[i] - last < SPACING) continue;
            double[] pt = {p.x[i], p.z[i]};
            if (i < 4 || i >= n - 4) continue;
            boolean far = true;
            for (Field f : p.fields) far &= Math.hypot(f.fx - pt[0], f.fz - pt[1]) >= Field.APART;
            if (!far) continue;
            Field f = candidate(p, terrain, i, seed, k);
            if (f == null) continue;
            if (Field.u(seed, 1, k, 0) >= Math.min(1.0, 0.6 * Field.DENSITY)) continue;
            p.fields.add(f);
            last = arc[i];
        }
    }

    private static Field candidate(EdgePlan p, TerrainSampler terrain, int i, long seed, int k) {
        double tx = p.x[i + 3] - p.x[i - 3], tz = p.z[i + 3] - p.z[i - 3], l = Math.hypot(tx, tz);
        if (l < 1e-3) return null;
        tx /= l;
        tz /= l;
        double fx = p.x[i], fz = p.z[i], nx = -tz, nz = tx;
        long fseed = Field.mix(seed ^ (k * 0x9E3779B97F4A7C15L));
        Field probe = new Field(fx, fz, tx, tz, Math.round(p.y[i]), fseed, null, new float[0], new float[0], new float[0]);
        // every road sample under the blob (and a margin) is plain ground; no village decor stands in it
        java.util.List<float[]> road = new java.util.ArrayList<>();
        for (int j = 0; j < p.length(); j++) {
            if (!probe.covers(p.x[j], p.z[j], 12)) continue;
            if (p.kind[j] != EdgePlan.GROUND) return null;
            road.add(new float[]{p.x[j], p.z[j], p.hw[j]});
        }
        for (EdgePlan.Decor d : p.decor)
            if (d.type() != EdgePlan.PROP && Math.hypot(d.x() - fx, d.z() - fz) < 50) return null;
        // ground and biome under the centre and round the edge (and half way): gentle, dry, plains
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int q = -1; q < 32; q++) {
            double[] at = q < 0 ? new double[]{fx, fz} : probe.at(2 * Math.PI * (q % 16) / 16, q < 16 ? 1.0 : 0.5);
            double x = at[0], z = at[1];
            TerrainSampler.Sample s = terrain.sample((int) Math.floor(x), (int) Math.floor(z));
            if (s.wet()) return null;
            var b = terrain.biome((int) Math.floor(x), s.ground(), (int) Math.floor(z));
            if (!b.is(Biomes.PLAINS) && !b.is(Biomes.SUNFLOWER_PLAINS)) return null;
            min = Math.min(min, s.ground());
            max = Math.max(max, s.ground());
        }
        if (max - min > 6) return null;
        float[] rx = new float[road.size()], rz = new float[road.size()], rh = new float[road.size()];
        for (int j = 0; j < rx.length; j++) {
            rx[j] = road.get(j)[0];
            rz[j] = road.get(j)[1];
            rh[j] = road.get(j)[2];
        }
        int[] scare = Field.u(fseed, 4, 0, 0) < 0.8 ? scarecrow(p, terrain, probe, fx, fz, tx, tz, nx, nz, fseed, road) : null;
        return new Field(fx, fz, tx, tz, Math.round(p.y[i]), fseed, scare, rx, rz, rh);
    }

    /** A pole column 8-14 blocks off the road inside the field with its two arm columns level and in one chunk; null when none fits. */
    private static int[] scarecrow(EdgePlan p, TerrainSampler terrain, Field f, double fx, double fz, double tx, double tz, double nx, double nz, long seed,
                                   java.util.List<float[]> road) {
        int side = Field.u(seed, 5, 0, 0) < 0.5 ? 1 : -1;
        int ax = Math.abs(tx) >= Math.abs(tz) ? 1 : 0, az = 1 - ax;
        for (int attempt = 0; attempt < 14; attempt++) {
            double v = side * (8 + Field.u(seed, 6, attempt, 0) * 6.99), w = (Field.u(seed, 7, attempt, 0) - 0.5) * 22;
            int px = (int) Math.floor(fx + tx * w + nx * v), pz = (int) Math.floor(fz + tz * w + nz * v);
            if ((px - ax) >> 4 != px >> 4 || (px + ax) >> 4 != px >> 4 || (pz - az) >> 4 != pz >> 4 || (pz + az) >> 4 != pz >> 4) continue;
            boolean ok = true;
            int g = Integer.MIN_VALUE;
            for (int d = -1; d <= 1 && ok; d++) {
                int x = px + ax * d, z = pz + az * d;
                if (f.state(x, z) != 2 || f.norm(x, z) > -3.0 / Field.RZ || f.pocketCell(x, z)) ok = false;
                TerrainSampler.Sample s = terrain.sample(x, z);
                if (s.wet() || g != Integer.MIN_VALUE && s.ground() != g) ok = false;
                g = s.ground();
            }
            if (!ok) continue;
            for (float[] r : road) if (Math.hypot(r[0] - (px + 0.5), r[1] - (pz + 0.5)) < r[2] + 7) ok = false;
            if (!ok) continue;
            double toward = ax == 1 ? -nz * side : -nx * side;
            int fdx = ax == 1 ? 0 : (toward >= 0 ? 1 : -1), fdz = ax == 1 ? (toward >= 0 ? 1 : -1) : 0;
            return new int[]{px, pz, ax, az, fdx, fdz};
        }
        return null;
    }

    /** Fields touching a camp are dropped (camps are chosen after the decor). */
    static void dropNearCamps(EdgePlan p) {
        if (p.campBoxes.isEmpty() || p.fields.isEmpty()) return;
        p.fields.removeIf(f -> {
            for (var b : p.campBoxes) {
                int cx = Math.clamp((int) Math.floor(f.fx), b.minX(), b.maxX()), cz = Math.clamp((int) Math.floor(f.fz), b.minZ(), b.maxZ());
                if (f.covers(cx, cz, 8)) return true;
            }
            return false;
        });
    }
}
