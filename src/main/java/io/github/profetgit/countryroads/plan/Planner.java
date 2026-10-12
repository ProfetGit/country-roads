package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.paint.Palette;
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
    private final Camps camps;

    public Planner(TerrainSampler terrain, VillageOracle oracle) {
        this.terrain = terrain;
        this.oracle = oracle;
        this.router = new Router(terrain);
        this.camps = null;
    }

    public Planner(TerrainSampler terrain, VillageOracle oracle, Camps camps) {
        this.terrain = terrain;
        this.oracle = oracle;
        this.router = new Router(terrain);
        this.camps = camps;
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

        float[] x = new float[n], z = new float[n], ground = new float[n], water = new float[n], hw = new float[n];
        boolean[] river = new boolean[n];
        byte[] wood = new byte[n];
        for (int i = 0; i < n; i++) {
            double[] p = line.get(start + i);
            x[i] = (float) p[0];
            z[i] = (float) p[1];
        }
        for (int i = 0; i < n; i++) {
            TerrainSampler.Sample s = terrain.sample((int) Math.floor(x[i]), (int) Math.floor(z[i]));
            ground[i] = s.ground();
            water[i] = s.water();
            river[i] = s.river();
            if (i % 16 == 0) {
                byte w = Palette.wood(terrain.biome((int) x[i], s.water(), (int) z[i]));
                for (int k = i; k < Math.min(n, i + 16); k++) wood[k] = w;
            }
        }
        byte[] kind = new byte[n];
        float[] y = profile(ground, water, river, kind);
        long seed = e.seed();
        double p1 = (seed & 0xffff) / 65536.0 * Math.PI * 2, p2 = ((seed >> 16) & 0xffff) / 65536.0 * Math.PI * 2;
        for (int i = 0; i < n; i++) hw[i] = (float) (1.7 + 0.22 * Math.sin(i * 0.071 + p1) + 0.12 * Math.sin(i * 0.193 + p2));
        EdgePlan plan = new EdgePlan(e, x, z, y, hw, kind, wood, System.nanoTime() - t0);
        plan.nameA = NameGen.name(e.a().key(), Palette.surface(terrain.biome(e.a().pos().getX(), e.a().pos().getY(), e.a().pos().getZ())));
        plan.nameB = NameGen.name(e.b().key(), Palette.surface(terrain.biome(e.b().pos().getX(), e.b().pos().getY(), e.b().pos().getZ())));
        decorate(plan);
        if (camps != null) camps.choose(plan);
        plan.decor.removeIf(d -> !standable(d));
        return plan;
    }

    /** Signpost at each village end (pointing to the other village), lamp posts on the last 30 blocks into each village. */
    void decorate(EdgePlan p) {
        int n = p.length();
        if (n < 40) return;
        int dist = Math.round(n / 10f) * 10;
        signEither(p, 4, 1, p.nameB, dist, p.nameA);
        signEither(p, n - 5, -1, p.nameA, dist, p.nameB);
        for (int k = 1; k <= 3; k++) {
            lamp(p, 10 * k, 1, k % 2 == 0 ? 1 : -1);
            lamp(p, n - 1 - 10 * k, -1, k % 2 == 0 ? 1 : -1);
        }
    }

    private static float[] side(EdgePlan p, int i, int dir, float offset, int sideSign) {
        int a = Math.clamp(i - 3, 0, p.length() - 1), b = Math.clamp(i + 3, 0, p.length() - 1);
        float tx = (p.x[b] - p.x[a]) * dir, tz = (p.z[b] - p.z[a]) * dir;
        float len = (float) Math.hypot(tx, tz);
        tx /= len;
        tz /= len;
        float rx = -tz * sideSign, rz = tx * sideSign;
        float d = p.hw[i] + offset;
        return new float[]{p.x[i] + rx * d, p.z[i] + rz * d, tx, tz};
    }

    /** Dry ground near the road's height under the post, from the generator's own terrain. */
    private boolean standable(EdgePlan.Decor d) {
        TerrainSampler.Sample s = terrain.sample(d.x(), d.z());
        return !s.wet() && Math.abs(s.ground() - d.y()) <= 3;
    }

    /** The sign goes on the right-hand side, else the left, else a few blocks further out. */
    private void signEither(EdgePlan p, int i, int dir, String to, int dist, String here) {
        for (int k = 0; k < 4; k++) {
            int j = i + dir * 3 * (k / 2);
            if (j < 0 || j >= p.length()) continue;
            int before = p.decor.size();
            sign(p, j, dir, to, dist, here, k % 2 == 0 ? 1 : -1);
            if (p.decor.size() > before) {
                if (standable(p.decor.getLast())) return;
                p.decor.removeLast();
            }
        }
    }

    private static void sign(EdgePlan p, int i, int dir, String to, int dist, String here, int sideSign) {
        if (p.kind[i] != EdgePlan.GROUND) return;
        float[] s = side(p, i, dir, 1.4f, sideSign);
        double theta = Math.atan2(s[2], -s[3]);
        int rot = Math.floorMod((int) Math.round(Math.toDegrees(theta) / 22.5), 16);
        p.decor.add(new EdgePlan.Decor((int) Math.floor(s[0]), (int) Math.floor(s[1]), p.y[i], EdgePlan.SIGN, (byte) rot, to, dist + " blocks", here));
    }

    private static void lamp(EdgePlan p, int i, int dir, int sideSign) {
        if (i < 0 || i >= p.length() || p.kind[i] != EdgePlan.GROUND) return;
        float[] s = side(p, i, dir, 1.3f, sideSign);
        p.decor.add(new EdgePlan.Decor((int) Math.floor(s[0]), (int) Math.floor(s[1]), p.y[i], EdgePlan.LAMP, (byte) 0, "", "", ""));
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

    /**
     * Surface heights and segment kinds. Dry ground is smoothed and grade-limited; water becomes a boardwalk (shallow,
     * not a river) or a bridge with a flat deck; deep cuts become tunnels, high fills bridges (trestles).
     */
    static float[] profile(float[] ground, float[] water, boolean[] river, byte[] kind) {
        int n = ground.length;
        float[] base = new float[n];
        for (int i = 0; i < n; i++) {
            boolean wet = water[i] > ground[i];
            float depth = water[i] - ground[i];
            if (!wet) {
                base[i] = ground[i];
                kind[i] = EdgePlan.GROUND;
            } else if (!river[i] && depth <= 3) {
                base[i] = water[i] + 1;
                kind[i] = EdgePlan.BOARDWALK;
            } else {
                base[i] = water[i] + 3;
                kind[i] = EdgePlan.BRIDGE;
            }
        }
        // short dry gaps inside a crossing (islands, sandbars) join it; tiny wet specks stay ground
        smoothKinds(kind, EdgePlan.GROUND, 6);
        for (int i = 0; i < n; i++) if (kind[i] == EdgePlan.BOARDWALK && river[i]) kind[i] = EdgePlan.BRIDGE;

        float[] y = new float[n];
        int r = 4;
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
        // decks are level across each crossing
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == EdgePlan.BRIDGE || kind[i] == EdgePlan.BOARDWALK) {
                float deck = Float.NEGATIVE_INFINITY;
                for (int k = i; k <= j; k++) deck = Math.max(deck, base[k]);
                for (int k = i; k <= j; k++) y[k] = deck;
            }
            i = j + 1;
        }
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < n; i++) if (kind[i] != EdgePlan.GROUND) y[i] = Math.max(y[i], base[i]);
            for (int i = 1; i < n; i++) y[i] = Math.clamp(y[i], y[i - 1] - MAX_GRADE, y[i - 1] + MAX_GRADE);
            for (int i = n - 2; i >= 0; i--) y[i] = Math.clamp(y[i], y[i + 1] - MAX_GRADE, y[i + 1] + MAX_GRADE);
        }
        // dry stretches far below the ground are tunnels, far above it trestle bridges
        for (int i = 0; i < n; i++) {
            if (kind[i] != EdgePlan.GROUND) continue;
            if (y[i] < ground[i] - 7) kind[i] = EdgePlan.TUNNEL;
            else if (y[i] > ground[i] + 5) kind[i] = EdgePlan.BRIDGE;
        }
        dropShortRuns(kind, EdgePlan.TUNNEL, 10);
        return y;
    }

    /** Runs of `fill` shorter than max between two equal non-fill kinds take that kind. */
    static void smoothKinds(byte[] kind, byte fill, int max) {
        int n = kind.length;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == fill && i > 0 && j + 1 < n && kind[i - 1] == kind[j + 1] && j - i + 1 <= max)
                for (int k = i; k <= j; k++) kind[k] = kind[i - 1];
            else if (kind[i] != fill && j - i + 1 <= 2 && (i == 0 || kind[i - 1] == fill) && (j + 1 >= n || kind[j + 1] == fill))
                for (int k = i; k <= j; k++) kind[k] = fill;
            i = j + 1;
        }
    }

    static void dropShortRuns(byte[] kind, byte k0, int min) {
        int n = kind.length;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == k0 && j - i + 1 < min) for (int k = i; k <= j; k++) kind[k] = EdgePlan.GROUND;
            i = j + 1;
        }
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
