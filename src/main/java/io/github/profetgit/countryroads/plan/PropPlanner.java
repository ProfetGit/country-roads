package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.paint.Props;
import io.github.profetgit.countryroads.world.TerrainSampler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/**
 * Roadside props (lost carts, milestones and hidden stashes, wayside shrines, view benches, hay carts and bales) for one
 * road. A prop is a Decor of type PROP: its anchor column and the direction its template's local +z points to. Samples
 * are one block apart, so sample counts below are blocks. -Dcountryroads.propDensity=F (default 1) multiplies the lost
 * cart, shrine, bench and farm chances and divides the milestone spacing and the bench spacing (testing).
 */
final class PropPlanner {
    static final double DENSITY = Double.parseDouble(System.getProperty("countryroads.propDensity", "1"));
    private static final float[] EXTRA = {0f, 1f, 2f};

    private final EdgePlan p;
    private final TerrainSampler terrain;
    private final int n;
    private final double[] arc;
    private final double len;
    private final long seed;

    private PropPlanner(EdgePlan p, TerrainSampler terrain) {
        this.p = p;
        this.terrain = terrain;
        this.n = p.length();
        this.arc = new double[n];
        for (int i = 1; i < n; i++) arc[i] = arc[i - 1] + Math.hypot(p.x[i] - p.x[i - 1], p.z[i] - p.z[i - 1]);
        this.len = arc[n - 1];
        this.seed = mix(p.edge.seed() ^ 0x7072_6F70_73L);
    }

    static void plan(EdgePlan p, TerrainSampler terrain) {
        if (!Props.OK || p.length() < 120) return;
        new PropPlanner(p, terrain).run();
    }

    /** Props whose footprint touches a camp (planned after the props) are dropped. */
    static void dropNearCamps(EdgePlan p) {
        if (p.campBoxes.isEmpty()) return;
        p.decor.removeIf(d -> {
            if (d.type() != EdgePlan.PROP) return false;
            for (var b : p.campBoxes)
                if (d.x() >= b.minX() - 8 && d.x() <= b.maxX() + 8 && d.z() >= b.minZ() - 8 && d.z() <= b.maxZ() + 8) return true;
            return false;
        });
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private double unit(long k) {
        return (mix(seed + k * 0x9E3779B97F4A7C15L) >>> 11) / (double) (1L << 53);
    }

    private int indexAt(double a) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arc[mid] < a) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void run() {
        double aStart = arc[Math.min(p.connA, n - 1)], aEnd = arc[Math.max(0, n - 1 - p.connB)];
        // farm props near each village
        for (int end = 0; end < 2; end++) {
            if (unit(31 + end) >= Math.min(1.0, 0.6 * DENSITY)) continue;
            int prop = unit(41 + end) < 0.4 ? Props.HAY_CART : Props.HAY_BALES;
            int side = unit(51 + end) < 0.5 ? 1 : -1, start = (int) (unit(61 + end) * 34);
            boolean done = false;
            for (int t = 0; t < 34 && !done; t++) {
                int off = 12 + (start + t) % 34;
                int i = indexAt(end == 0 ? aStart + off : aEnd - off);
                done = tryAt(prop, i, side, 2) || tryAt(prop, i, -side, 2);
            }
        }
        benches();
        if (len >= 400) {
            double e = len / 1500 * DENSITY;
            int count = (int) e + (unit(21) < e - (int) e ? 1 : 0);
            for (int k = 0; k < count; k++)
                for (int attempt = 0; attempt < 10; attempt++) {
                    double a = 80 + unit(100 + k * 16 + attempt) * (len - 160);
                    if (tryAt(Props.LOST_CART, indexAt(a), unit(300 + k * 16 + attempt) < 0.5 ? 1 : -1, 6)) break;
                }
        }
        if (len >= 150) {
            double e = len / 1200 * DENSITY;
            int count = (int) e + (unit(22) < e - (int) e ? 1 : 0);
            for (int k = 0; k < count; k++)
                for (int attempt = 0; attempt < 10; attempt++) {
                    double a = 60 + unit(500 + k * 16 + attempt) * (len - 120);
                    if (tryAt(Props.SHRINE, indexAt(a), unit(700 + k * 16 + attempt) < 0.5 ? 1 : -1, 6)) break;
                }
        }
        // a milestone every 100 blocks from the villages' connectors, all on one side; one in three hides a stash
        double a0 = aStart + 30, a1 = aEnd - 30, step = Math.max(12, 100 / DENSITY);
        int side = unit(23) < 0.5 ? 1 : -1;
        for (int k = 0; a0 + k * step <= a1; k++) {
            int prop = unit(900 + k) < 1.0 / 3 ? Props.STASH : Props.MILESTONE;
            tryAt(prop, indexAt(a0 + k * step), side, 8);
        }
    }

    private record Cand(double arc, int side, double drop) {}

    private static final int[] VIEW = {19, 13, 25, 16, 22, 10, 28};

    private void benches() {
        double e = len / 500 * DENSITY;
        int max = (int) e + (unit(24) < e - (int) e ? 1 : 0);
        if (max <= 0) return;
        Props.Prop pr = Props.get(Props.BENCH);
        List<Cand> cands = new ArrayList<>();
        for (double a = 40; a <= len - 40; a += 20)
            for (int side = 1; side >= -1; side -= 2) {
                int i = indexAt(a);
                if (!groundRun(i)) continue;
                double[] g = geometry(i, side, pr.zNear);
                if (g == null) continue;
                int x = (int) Math.floor(g[0]), z = (int) Math.floor(g[1]);
                TerrainSampler.Sample s = terrain.sample(x, z);
                if (s.wet() || Math.abs(s.ground() - p.y[i]) > 1.0) continue;
                double drop = 0;
                for (int d : VIEW) {
                    int vx = (int) Math.floor(g[0] + g[2] * d), vz = (int) Math.floor(g[1] + g[3] * d);
                    drop = Math.max(drop, p.y[i] - terrain.sample(vx, vz).ground());
                }
                if (drop >= 7) cands.add(new Cand(a, side, drop));
            }
        cands.sort(Comparator.comparingDouble(Cand::drop).reversed());
        List<Double> chosen = new ArrayList<>();
        double apart = Math.max(24, 300 / DENSITY);
        for (Cand c : cands) {
            if (chosen.size() >= max) break;
            boolean far = true;
            for (double o : chosen) far &= Math.abs(o - c.arc()) >= apart;
            if (far && tryAt(Props.BENCH, indexAt(c.arc()), c.side(), 3)) chosen.add(c.arc());
        }
    }

    private boolean groundRun(int i) {
        if (i < 4 || i >= n - 4) return false;
        for (int k = i - 3; k <= i + 3; k++) if (p.kind[k] != EdgePlan.GROUND || p.shared != null && p.shared[k]) return false;
        return Math.abs(p.y[i + 3] - p.y[i - 3]) <= 2.5f && (p.earth == null || p.earth[i] <= 2);
    }

    /** {anchor x, anchor z (exact), unit outward x, unit outward z, tangent x, tangent z} at sample i, side +1 right / -1 left. */
    private double[] geometry(int i, int side, int zNear) {
        double tx = p.x[i + 3] - p.x[i - 3], tz = p.z[i + 3] - p.z[i - 3], l = Math.hypot(tx, tz);
        if (l < 1e-3) return null;
        tx /= l;
        tz /= l;
        double rx = -tz * side, rz = tx * side, off = p.hw[i] + 1.5 + zNear;
        return new double[]{p.x[i] + rx * off, p.z[i] + rz * off, rx, rz, tx, tz};
    }

    private boolean tryAt(int propId, int i0, int side, int maxShift) {
        Props.Prop pr = Props.get(propId);
        for (int shift = 0; shift <= maxShift; shift++) {
            int i = i0 + (shift % 2 == 0 ? shift / 2 : -(shift / 2 + 1));
            if (!groundRun(i)) continue;
            for (float extra : EXTRA) {
                double[] g = geometry(i, side, pr.zNear);
                if (g == null) break;
                double off = extra;
                int x = (int) Math.floor(g[0] + g[2] * off), z = (int) Math.floor(g[1] + g[3] * off);
                Direction toRoad = Direction.getApproximateNearest((float) -g[2], 0, (float) -g[3]);
                Direction dir = pr.away ? toRoad.getOpposite() : toRoad;
                if (!Props.fitsChunk(pr, x, z, dir) || inField(x, z)) continue;
                if (!clearOfRoad(pr, x, z, dir, i) || !spaced(pr, x, z, dir) || !groundOk(propId, pr, x, z, dir, i)) continue;
                Direction along = Direction.getApproximateNearest((float) g[4], 0, (float) g[5]);
                if (along.getAxis() == toRoad.getAxis()) along = side > 0 ? toRoad.getCounterClockWise() : toRoad.getClockWise();
                EdgePlan.Decor d = new EdgePlan.Decor(x, z, p.y[i], EdgePlan.PROP, dir, along, pr.name, "", "");
                p.decor.add(d);
                return true;
            }
        }
        return false;
    }

    private boolean inField(int x, int z) {
        for (Field f : p.fields) if (f.covers(x, z, 4)) return true;
        return false;
    }

    /** Every above-ground cell stays clear of the road (a prop beside a diagonal road would reach over its near end). */
    private boolean clearOfRoad(Props.Prop pr, int ax, int az, Direction dir, int i) {
        Rotation r = Props.rotation(dir);
        int lo = Math.max(0, i - 16), hi = Math.min(n - 1, i + 16);
        for (int xl = pr.aboveMinX; xl <= pr.aboveMaxX; xl++)
            for (int zl = pr.aboveMinZ; zl <= pr.aboveMaxZ; zl++) {
                if (xl != pr.aboveMinX && xl != pr.aboveMaxX && zl != pr.aboveMinZ && zl != pr.aboveMaxZ) continue;
                double cx = ax + Props.rx(r, xl, zl) + 0.5, cz = az + Props.rz(r, xl, zl) + 0.5;
                for (int j = lo; j <= hi; j++)
                    if (Math.hypot(cx - p.x[j], cz - p.z[j]) < p.hw[j] + 1.0) return false;
            }
        return true;
    }

    private boolean spaced(Props.Prop pr, int ax, int az, Direction dir) {
        Rotation r = Props.rotation(dir);
        int[] bx = {Props.rx(r, pr.minX, pr.minZ), Props.rx(r, pr.maxX, pr.minZ), Props.rx(r, pr.minX, pr.maxZ), Props.rx(r, pr.maxX, pr.maxZ)};
        int[] bz = {Props.rz(r, pr.minX, pr.minZ), Props.rz(r, pr.maxX, pr.minZ), Props.rz(r, pr.minX, pr.maxZ), Props.rz(r, pr.maxX, pr.maxZ)};
        int x0 = ax + java.util.Arrays.stream(bx).min().getAsInt(), x1 = ax + java.util.Arrays.stream(bx).max().getAsInt();
        int z0 = az + java.util.Arrays.stream(bz).min().getAsInt(), z1 = az + java.util.Arrays.stream(bz).max().getAsInt();
        for (EdgePlan.Decor d : p.decor) {
            double dist = Math.hypot(d.x() - ax, d.z() - az);
            if (d.type() == EdgePlan.PROP) {
                if (dist < 24) return false;
            } else {
                double gx = Math.max(Math.max(x0 - d.x(), d.x() - x1), 0), gz = Math.max(Math.max(z0 - d.z(), d.z() - z1), 0);
                if (dist < 8 || Math.hypot(gx, gz) < 3) return false;
            }
        }
        return true;
    }

    /** The generator's ground under the anchor and the footprint corners: dry, near the road's height, level enough. */
    private boolean groundOk(int propId, Props.Prop pr, int ax, int az, Direction dir, int i) {
        TerrainSampler.Sample a = terrain.sample(ax, az);
        if (a.wet() || Math.abs(a.ground() - p.y[i]) > (propId == Props.BENCH ? 1.0 : 2.0)) return false;
        Rotation r = Props.rotation(dir);
        for (int k = 0; k < 4; k++) {
            int xl = (k & 1) == 0 ? pr.aboveMinX : pr.aboveMaxX, zl = (k & 2) == 0 ? pr.aboveMinZ : pr.aboveMaxZ;
            TerrainSampler.Sample s = terrain.sample(ax + Props.rx(r, xl, zl), az + Props.rz(r, xl, zl));
            if (s.wet() || s.ground() > a.ground() + 1 || s.ground() < a.ground() - 3) return false;
        }
        return true;
    }
}
