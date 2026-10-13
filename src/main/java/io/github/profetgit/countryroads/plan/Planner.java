package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.paint.Palette;
import io.github.profetgit.countryroads.route.Router;
import io.github.profetgit.countryroads.world.TerrainSampler;
import io.github.profetgit.countryroads.world.VillageOracle;
import java.util.ArrayList;
import net.minecraft.core.Direction;
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
        return plan(e, List.of());
    }

    /** +1 when the other road lies to the left of travel (so the right side is clear), else -1. */
    private static int awaySide(List<double[]> line, int i, int dir, EdgePlan other, int j) {
        int a = Math.clamp(i - 3, 0, line.size() - 1), b = Math.clamp(i + 3, 0, line.size() - 1);
        double tx = (line.get(b)[0] - line.get(a)[0]) * dir, tz = (line.get(b)[1] - line.get(a)[1]) * dir;
        int k = Math.clamp(j, 0, other.length() - 1);
        double ox = other.x[k] - line.get(i)[0], oz = other.z[k] - line.get(i)[1];
        double cross = tx * oz - tz * ox;
        return cross > 0 ? -1 : 1;
    }

    private static boolean sameWay(Trunk t, VillageOracle.Village v, VillageOracle.Village to) {
        if (!(t.p.edge.a().equals(v) || t.p.edge.b().equals(v))) return false;
        VillageOracle.Village o = t.p.edge.a().equals(v) ? t.p.edge.b() : t.p.edge.a();
        double a1 = Math.atan2(to.pos().getZ() - v.pos().getZ(), to.pos().getX() - v.pos().getX());
        double a2 = Math.atan2(o.pos().getZ() - v.pos().getZ(), o.pos().getX() - v.pos().getX());
        return Math.abs(Math.atan2(Math.sin(a1 - a2), Math.cos(a1 - a2))) < Math.toRadians(70);
    }

    /** A planned road sharing a village with this one; atStart: its sample 0 is at the shared village. */
    private record Trunk(EdgePlan p, VillageOracle.Village shared, boolean atStart) {
        int end() {
            return atStart ? 0 : p.length() - 1;
        }
    }

    /**
     * trunks: already planned, shorter roads leaving one of this road's villages in a similar direction. This road
     * starts where the trunk starts, prefers to travel along it, and where the two share the way it takes the trunk's
     * exact line and heights, branching off smoothly with a junction signpost.
     */
    public EdgePlan plan(Edge e, List<EdgePlan> trunkPlans) {
        long t0 = System.nanoTime();
        VillageOracle.Footprint fa = oracle.footprint(e.a()), fb = oracle.footprint(e.b());
        int[] ja = joinPoint(fa, e.a(), e.b()), jb = joinPoint(fb, e.b(), e.a());
        List<Trunk> trunks = new ArrayList<>();
        for (EdgePlan t : trunkPlans) {
            VillageOracle.Village shared = t.edge.a().equals(e.a()) || t.edge.a().equals(e.b()) ? t.edge.a() : t.edge.b();
            trunks.add(new Trunk(t, shared, t.edge.a().equals(shared)));
        }
        Trunk trunkA = null, trunkB = null;
        for (Trunk t : trunks) {
            // share the trunk's street only when both roads leave the village the same way
            if (t.shared.equals(e.a()) && trunkA == null && sameWay(t, e.a(), e.b())) trunkA = t;
            if (t.shared.equals(e.b()) && trunkB == null && sameWay(t, e.b(), e.a())) trunkB = t;
        }
        if (trunkA != null) ja = new int[]{(int) Math.floor(trunkA.p.x[trunkA.end()]), (int) Math.floor(trunkA.p.z[trunkA.end()])};
        if (trunkB != null) jb = new int[]{(int) Math.floor(trunkB.p.x[trunkB.end()]), (int) Math.floor(trunkB.p.z[trunkB.end()])};
        it.unimi.dsi.fastutil.longs.LongOpenHashSet trunkCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (Trunk t : trunks)
            for (int i = 0; i < t.p.length(); i += 4)
                trunkCells.add(Router.cellKey(Math.floorDiv((int) t.p.x[i], Router.CELL), Math.floorDiv((int) t.p.z[i], Router.CELL)));
        Router.Result r = router.route(ja[0], ja[1], jb[0], jb[1], ELLIPSE, trunkCells::contains);
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

        // snap onto existing roads wherever this one runs alongside them: their exact line and heights
        int L = line.size();
        float[] pinY = new float[L];
        byte[] pinKind = new byte[L];
        java.util.Arrays.fill(pinY, Float.NaN);
        List<int[]> joins = new ArrayList<>();
        for (Trunk t : trunks) {
            int[] match = new int[L];
            java.util.Arrays.fill(match, -1);
            for (int i = 0; i < L; i++) {
                if (!Float.isNaN(pinY[i])) continue;
                double[] q = line.get(i);
                double bd = 81;
                for (int j = 0; j < t.p.length(); j++) {
                    double dx = t.p.x[j] - q[0], dz = t.p.z[j] - q[1], d2 = dx * dx + dz * dz;
                    if (d2 < bd) {
                        bd = d2;
                        match[i] = j;
                    }
                }
            }
            for (int i = 0; i < L; ) {
                if (match[i] < 0) {
                    i++;
                    continue;
                }
                int a = i, b = i, gap = 0;
                for (int k = i + 1; k < L && gap <= 6; k++) {
                    if (match[k] >= 0) {
                        b = k;
                        gap = 0;
                    } else gap++;
                }
                i = b + 1;
                if (b - a + 1 < 12) continue;
                double[] offA = null, offB = null;
                int j = match[a];
                for (int k = a; k <= b; k++) {
                    if (match[k] >= 0) j = match[k];
                    double[] q = line.get(k);
                    if (k == a) offA = new double[]{t.p.x[j] - q[0], t.p.z[j] - q[1]};
                    if (k == b) offB = new double[]{t.p.x[j] - q[0], t.p.z[j] - q[1]};
                    line.set(k, new double[]{t.p.x[j], t.p.z[j]});
                    pinY[k] = t.p.y[j];
                    pinKind[k] = t.p.kind[j];
                }
                // ease the free road into and out of the shared stretch
                for (int k = 1; k <= 14; k++) {
                    double w = 1 - k / 15.0;
                    if (a - k >= 0 && Float.isNaN(pinY[a - k])) {
                        double[] q = line.get(a - k);
                        line.set(a - k, new double[]{q[0] + offA[0] * w, q[1] + offA[1] * w});
                    }
                    if (b + k < L && Float.isNaN(pinY[b + k])) {
                        double[] q = line.get(b + k);
                        line.set(b + k, new double[]{q[0] + offB[0] * w, q[1] + offB[1] * w});
                    }
                }
                // the signpost goes on the side away from the other road
                if (a > 4) joins.add(new int[]{a - 4, -1, awaySide(line, a - 4, -1, t.p, match[a])});
                if (b < L - 5) joins.add(new int[]{b + 4, 1, awaySide(line, b + 4, 1, t.p, match[b])});
            }
        }

        int start = 0, end = line.size() - 1;
        while (start < end && fa.contains((int) Math.floor(line.get(start)[0]), (int) Math.floor(line.get(start)[1]), 1)) start++;
        while (end > start && fb.contains((int) Math.floor(line.get(end)[0]), (int) Math.floor(line.get(end)[1]), 1)) end--;
        start = Math.max(0, start - 1);
        end = Math.min(line.size() - 1, end + 1);
        int n = end - start + 1;
        if (n < 8) return null;

        float[] x = new float[n], z = new float[n], ground = new float[n], water = new float[n], hw = new float[n], pin = new float[n];
        byte[] kindPin = new byte[n];
        boolean[] river = new boolean[n];
        byte[] wood = new byte[n];
        for (int i = 0; i < n; i++) {
            double[] p = line.get(start + i);
            x[i] = (float) p[0];
            z[i] = (float) p[1];
            pin[i] = pinY[start + i];
            kindPin[i] = pinKind[start + i];
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
        // village ends meet the street at its own height
        if (Float.isNaN(pin[0]) && water[0] <= ground[0]) pin[0] = ground[0];
        if (Float.isNaN(pin[n - 1]) && water[n - 1] <= ground[n - 1]) pin[n - 1] = ground[n - 1];
        byte[] kind = new byte[n];
        float[] y = profile(ground, water, river, kind, pin, kindPin);
        long seed = e.seed();
        double p1 = (seed & 0xffff) / 65536.0 * Math.PI * 2, p2 = ((seed >> 16) & 0xffff) / 65536.0 * Math.PI * 2;
        for (int i = 0; i < n; i++) hw[i] = (float) (1.75 + 0.35 * Math.sin(i * 0.045 + p1) + 0.18 * Math.sin(i * 0.13 + p2));
        EdgePlan plan = new EdgePlan(e, x, z, y, hw, kind, wood, System.nanoTime() - t0);
        plan.earth = new byte[n];
        for (int i = 0; i < n; i++) {
            int worst = 0;
            for (int k = Math.max(0, i - 6); k <= Math.min(n - 1, i + 6); k++)
                worst = Math.max(worst, kind[k] != EdgePlan.GROUND ? 99 : Math.round(Math.abs(y[k] - ground[k])));
            plan.earth[i] = (byte) Math.min(99, worst);
        }
        plan.nameA = NameGen.name(e.a().key(), Palette.surface(terrain.biome(e.a().pos().getX(), e.a().pos().getY(), e.a().pos().getZ())));
        plan.nameB = NameGen.name(e.b().key(), Palette.surface(terrain.biome(e.b().pos().getX(), e.b().pos().getY(), e.b().pos().getZ())));
        decorate(plan);
        for (int[] jt : joins) {
            // junction signpost where this road leaves a shared stretch: its destination that way, the other village back
            int jn = jt[0] - start, dir = jt[1];
            if (jn <= 4 || jn >= n - 5) continue;
            boolean towardB = dir > 0;
            String to = towardB ? plan.nameB : plan.nameA, back = towardB ? plan.nameA : plan.nameB;
            int toDist = Math.round((towardB ? n - jn : jn) / 10f) * 10, backDist = Math.round((towardB ? jn : n - jn) / 10f) * 10;
            post(plan, jn, dir, jt[2], EdgePlan.SIGN, to, toDist + " blocks", back + "\n" + backDist + " blocks");
        }
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
            post(p, 10 * k, 1, k % 2 == 0 ? 1 : -1, EdgePlan.LAMP, "", "", "");
            post(p, n - 1 - 10 * k, -1, k % 2 == 0 ? 1 : -1, EdgePlan.LAMP, "", "", "");
        }
        // orchard verge: a few blossoming fruit trees on one side of the last stretch into each village
        long oh = p.edge.seed() ^ 0x0C4A2DL;
        for (int end = 0; end < 2; end++) {
            int side = ((oh >>> (end + 5)) & 1) == 0 ? 1 : -1;
            for (int k = 0; k < 4; k++) {
                int i = 14 + 9 * k + (int) ((oh >>> (8 + k * 4 + end * 16)) & 3);
                if (i >= n / 2) break;
                if (end == 0) post(p, i, 1, side, EdgePlan.ORCHARD, "", "", "", 4.5f, 2);
                else post(p, n - 1 - i, -1, side, EdgePlan.ORCHARD, "", "", "", 4.5f, 2);
            }
        }
        runs(p);
    }

    /**
     * Countryside dry-stone walls and split-rail fences along some stretches: a run every 80-260 blocks, 12-44 long,
     * on one side or (rarely) both, only on plain ground and away from the village ends and posts.
     */
    static void runs(EdgePlan p) {
        int n = p.length();
        long h = p.edge.seed() ^ 0x5DEECE66DL;
        int s = 30 + (int) Math.floorMod(h, 120L);
        while (s < n - 30) {
            h = h * 6364136223846793005L + 1442695040888963407L;
            long t = Math.floorMod(h >>> 41, 20L);
            byte type = t < 9 ? EdgePlan.STONE_WALL : t < 15 ? EdgePlan.FENCE : EdgePlan.HEDGE;
            // split-rail fences stay short (user, 2026-10-13: long fence lines look wrong)
            int len = type == EdgePlan.FENCE ? 6 + (int) Math.floorMod(h >>> 33, 8L) : 12 + (int) Math.floorMod(h >>> 33, 33L);
            int e = Math.min(n - 30, s + len);
            boolean ok = e - s >= (type == EdgePlan.FENCE ? 6 : 10);
            for (int k = s; k <= e && ok; k++) ok = p.kind[k] == EdgePlan.GROUND;
            for (EdgePlan.Decor d : p.decor) {
                int near = nearestSample(p, d.x() + 0.5f, d.z() + 0.5f);
                if (near >= s - 6 && near <= e + 6) ok = false;
            }
            if (ok) {
                int r = (int) Math.floorMod(h >>> 17, 10L);
                byte side = (byte) (r < 2 && type != EdgePlan.FENCE ? 0 : r < 6 ? 1 : -1);
                p.runs.add(new EdgePlan.Run(s, e, side, type));
            }
            s = e + 80 + (int) Math.floorMod(h >>> 25, 180L);
        }
    }

    private static int nearestSample(EdgePlan p, float x, float z) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < p.length(); i += 2) {
            float d = (p.x[i] - x) * (p.x[i] - x) + (p.z[i] - z) * (p.z[i] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
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
            post(p, j, dir, k % 2 == 0 ? 1 : -1, EdgePlan.SIGN, to, dist + " blocks", here);
            if (p.decor.size() > before) {
                if (standable(p.decor.getLast())) return;
                p.decor.removeLast();
            }
        }
    }

    /**
     * A post 1.5 blocks outside the road edge. Its footprint (post plus the neighbour toward the road and the one along
     * it) must sit inside one chunk, so posts near a chunk edge slide along the road a little.
     */
    private static void post(EdgePlan p, int i0, int dir, int sideSign, byte type, String f1, String f2, String back) {
        post(p, i0, dir, sideSign, type, f1, f2, back, 1.5f, 1);
    }

    /** extra: distance outside the road edge; margin: columns the footprint needs on every side inside the chunk. */
    private static void post(EdgePlan p, int i0, int dir, int sideSign, byte type, String f1, String f2, String back, float extra, int margin) {
        for (int shift = 0; shift <= 6; shift++) {
            int i = i0 + (shift % 2 == 0 ? shift / 2 : -(shift / 2 + 1)) * dir;
            if (i < 3 || i >= p.length() - 3 || p.kind[i] != EdgePlan.GROUND) continue;
            int a = i - 3, b = i + 3;
            float tx = (p.x[b] - p.x[a]) * dir, tz = (p.z[b] - p.z[a]) * dir;
            float len = (float) Math.hypot(tx, tz);
            tx /= len;
            tz /= len;
            float rx = -tz * sideSign, rz = tx * sideSign;
            if (type == EdgePlan.ORCHARD && p.earth != null && p.earth[i] > 1) continue;
            float off = p.hw[i] + extra;
            int x = (int) Math.floor(p.x[i] + rx * off), z = (int) Math.floor(p.z[i] + rz * off);
            int lx = x & 15, lz = z & 15;
            if (lx < margin || lx > 15 - margin || lz < margin || lz > 15 - margin) continue;
            Direction toRoad = Direction.getApproximateNearest(-rx, 0, -rz);
            Direction along = Direction.getApproximateNearest(tx, 0, tz);
            if (along.getAxis() == toRoad.getAxis()) along = sideSign > 0 ? toRoad.getCounterClockWise() : toRoad.getClockWise();
            p.decor.add(new EdgePlan.Decor(x, z, p.y[i], type, toRoad, along, f1, f2, back));
            return;
        }
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
        float[] pin = new float[ground.length];
        java.util.Arrays.fill(pin, Float.NaN);
        return profile(ground, water, river, kind, pin, new byte[ground.length]);
    }

    /** pin: fixed heights (village streets, a shared trunk), NaN where free; pinKind: the trunk's kinds there. */
    static float[] profile(float[] ground, float[] water, boolean[] river, byte[] kind, float[] pin, byte[] pinKind) {
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
        for (int i = 0; i < n; i++) if (!Float.isNaN(pin[i]) && (i > 0 && i < n - 1 || pinKind[i] != 0)) kind[i] = pinKind[i];
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
        for (int pass = 0; pass < 6; pass++) {
            for (int i = 0; i < n; i++) if (kind[i] != EdgePlan.GROUND) y[i] = Math.max(y[i], base[i]);
            for (int i = 0; i < n; i++) if (!Float.isNaN(pin[i])) y[i] = pin[i];
            // a steeper grade is allowed on the last stretch into a village, so the road climbs into the street
            for (int i = 1; i < n; i++) {
                float g = i < 24 || i > n - 25 ? 0.5f : MAX_GRADE;
                if (Float.isNaN(pin[i])) y[i] = Math.clamp(y[i], y[i - 1] - g, y[i - 1] + g);
            }
            for (int i = n - 2; i >= 0; i--) {
                float g = i < 24 || i > n - 25 ? 0.5f : MAX_GRADE;
                if (Float.isNaN(pin[i])) y[i] = Math.clamp(y[i], y[i + 1] - g, y[i + 1] + g);
            }
        }
        // dry stretches far below the ground are tunnels, far above it trestle bridges
        for (int i = 0; i < n; i++) {
            if (kind[i] != EdgePlan.GROUND || !Float.isNaN(pin[i])) continue;
            if (y[i] < ground[i] - 9) kind[i] = EdgePlan.TUNNEL;
            else if (y[i] > ground[i] + 5) kind[i] = EdgePlan.BRIDGE;
        }
        dropShortRuns(kind, EdgePlan.TUNNEL, 16);
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
