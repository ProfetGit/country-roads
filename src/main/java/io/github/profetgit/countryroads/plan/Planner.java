package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.route.Router;
import io.github.profetgit.countryroads.world.TerrainSampler;
import io.github.profetgit.countryroads.world.VillageOracle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Turns a village pair into an EdgePlan: join points, coarse route, smoothed centreline, graded surface profile. */
public final class Planner {
    public static final double ELLIPSE = 1.6;
    /** Max planned rise per block of road (a half-step at most every 1.5 blocks). */
    static final float MAX_GRADE = 0.34f;

    private final TerrainSampler terrain;
    private final VillageOracle oracle;
    private final Router router;

    public Planner(TerrainSampler terrain, VillageOracle oracle) {
        this.terrain = terrain;
        this.oracle = oracle;
        this.router = new Router(terrain);
    }

    /** null when the pair gets no road (no land route, or far too costly). */
    public EdgePlan plan(Edge e) {
        long t0 = System.nanoTime();
        VillageOracle.Footprint fa = oracle.footprint(e.a()), fb = oracle.footprint(e.b());
        int[] ja = joinPoint(fa, e.a(), e.b()), jb = joinPoint(fb, e.b(), e.a());
        Router.Result r = router.route(ja[0], ja[1], jb[0], jb[1], ELLIPSE);
        if (r == null) return null;
        double straight = Math.hypot(jb[0] - ja[0], jb[1] - ja[1]);
        if (r.cost() > straight * 10 * 14) return null;

        List<double[]> pts = new ArrayList<>();
        for (int[] p : r.points()) pts.add(new double[]{p[0] + 0.5, p[1] + 0.5});
        pts.set(0, new double[]{ja[0] + 0.5, ja[1] + 0.5});
        pts.set(pts.size() - 1, new double[]{jb[0] + 0.5, jb[1] + 0.5});
        for (int k = 0; k < 4; k++) pts = chaikin(pts);
        List<double[]> line = resample(pts, 1.0);
        if (line.size() > straight * 2.3 + 64) return null;

        int start = 0, end = line.size() - 1;
        while (start < end && fa.contains((int) Math.floor(line.get(start)[0]), (int) Math.floor(line.get(start)[1]), 1)) start++;
        while (end > start && fb.contains((int) Math.floor(line.get(end)[0]), (int) Math.floor(line.get(end)[1]), 1)) end--;
        start = Math.max(0, start - 1);
        end = Math.min(line.size() - 1, end + 1);
        int n = end - start + 1;
        if (n < 8) return null;

        float[] x = new float[n], z = new float[n], base = new float[n], hw = new float[n];
        byte[] kind = new byte[n];
        TerrainSampler.Sample prev = null;
        for (int i = 0; i < n; i++) {
            double[] p = line.get(start + i);
            x[i] = (float) p[0];
            z[i] = (float) p[1];
        }
        // ground every 2 blocks, interpolated between
        for (int i = 0; i < n; i += 2) {
            TerrainSampler.Sample s = terrain.sample((int) Math.floor(x[i]), (int) Math.floor(z[i]));
            base[i] = s.wet() ? s.water() + 1 : s.ground();
            kind[i] = s.wet() ? EdgePlan.CAUSEWAY : EdgePlan.GROUND;
            if (i + 1 < n) {
                TerrainSampler.Sample s2 = terrain.sample((int) Math.floor(x[i + 1]), (int) Math.floor(z[i + 1]));
                base[i + 1] = s2.wet() ? s2.water() + 1 : s2.ground();
                kind[i + 1] = s2.wet() ? EdgePlan.CAUSEWAY : EdgePlan.GROUND;
            }
        }
        float[] y = profile(base, kind);
        long seed = e.seed();
        double p1 = (seed & 0xffff) / 65536.0 * Math.PI * 2, p2 = ((seed >> 16) & 0xffff) / 65536.0 * Math.PI * 2;
        for (int i = 0; i < n; i++) hw[i] = (float) (1.5 + 0.28 * Math.sin(i * 0.071 + p1) + 0.14 * Math.sin(i * 0.193 + p2));
        return new EdgePlan(e, x, z, y, hw, kind, System.nanoTime() - t0);
    }

    /** Outer street of the village toward the other village (centre of that street's box), else the village start. */
    static int[] joinPoint(VillageOracle.Footprint f, VillageOracle.Village self, VillageOracle.Village other) {
        BoundingBox best = null;
        double bd = Double.MAX_VALUE;
        for (BoundingBox b : f.streets()) {
            double cx = (b.minX() + b.maxX()) / 2.0, cz = (b.minZ() + b.maxZ()) / 2.0;
            double d = Math.hypot(cx - other.pos().getX(), cz - other.pos().getZ());
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best == null) return new int[]{self.pos().getX(), self.pos().getZ()};
        return new int[]{(best.minX() + best.maxX()) / 2, (best.minZ() + best.maxZ()) / 2};
    }

    /** Smoothed, grade-limited surface heights; water columns stay at least one block above the water. */
    static float[] profile(float[] base, byte[] kind) {
        int n = base.length;
        float[] y = new float[n];
        int r = 6;
        for (int i = 0; i < n; i++) {
            float sum = 0, w = 0;
            for (int k = -r; k <= r; k++) {
                int j = Math.clamp(i + k, 0, n - 1);
                float wt = r + 1 - Math.abs(k);
                sum += base[j] * wt;
                w += wt;
            }
            y[i] = sum / w;
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < n; i++) if (kind[i] == EdgePlan.CAUSEWAY) y[i] = Math.max(y[i], base[i]);
            for (int i = 1; i < n; i++) y[i] = Math.clamp(y[i], y[i - 1] - MAX_GRADE, y[i - 1] + MAX_GRADE);
            for (int i = n - 2; i >= 0; i--) y[i] = Math.clamp(y[i], y[i + 1] - MAX_GRADE, y[i + 1] + MAX_GRADE);
        }
        return y;
    }

    static List<double[]> chaikin(List<double[]> in) {
        if (in.size() < 3) return in;
        List<double[]> out = new ArrayList<>(in.size() * 2);
        out.add(in.getFirst());
        for (int i = 0; i + 1 < in.size(); i++) {
            double[] p = in.get(i), q = in.get(i + 1);
            out.add(new double[]{0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]});
            out.add(new double[]{0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]});
        }
        out.add(in.getLast());
        return out;
    }

    static List<double[]> resample(List<double[]> pts, double step) {
        List<double[]> out = new ArrayList<>();
        out.add(pts.getFirst());
        double carry = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] p = pts.get(i), q = pts.get(i + 1);
            double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
            double t = step - carry;
            while (t <= len) {
                out.add(new double[]{p[0] + (q[0] - p[0]) * t / len, p[1] + (q[1] - p[1]) * t / len});
                t += step;
            }
            carry = len - (t - step);
        }
        return out;
    }
}
