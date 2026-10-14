package io.github.profetgit.countryroads.route;

import io.github.profetgit.countryroads.world.TerrainSampler;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Coarse A* on a 16-block grid between two points. State = cell + heading, so turns cost and U-turns (switchbacks) are
 * impossible. Integer costs and a total-order tie-break keep it deterministic. Costs favour flat valleys, punish
 * slopes steeply and make water expensive (bridges) and oceans impassable.
 */
public final class Router {
    public static final int CELL = 16;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int MAX_EXPANSIONS = 80_000;

    public record Result(List<int[]> points, long cost, int expansions) {}

    private record Node(long state, long g, long f) {}

    private final TerrainSampler terrain;

    public Router(TerrainSampler terrain) {
        this.terrain = terrain;
    }

    private static long state(int i, int j, int h) {
        return (((long) i & 0x1fffffL) << 24) | (((long) j & 0x1fffffL) << 3) | h;
    }

    private static int si(long s) {
        return (int) (s << 19 >> 43);
    }

    private static int sj(long s) {
        return (int) (s << 40 >> 43);
    }

    private static int sh(long s) {
        return (int) (s & 7);
    }

    /** Route from (ax, az) to (bx, bz) block coords; null when no acceptable route exists. */
    public Result route(int ax, int az, int bx, int bz, double ellipseFactor) {
        return route(ax, az, bx, bz, ellipseFactor, k -> false);
    }

    public static long cellKey(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
    }

    /** trunk: coarse cells an existing road runs through; travelling along them is cheap (the road is already built). */
    public Result route(int ax, int az, int bx, int bz, double ellipseFactor, java.util.function.LongPredicate trunk) {
        int ai = Math.floorDiv(ax, CELL), aj = Math.floorDiv(az, CELL);
        int bi = Math.floorDiv(bx, CELL), bj = Math.floorDiv(bz, CELL);
        double d = Math.hypot(bi - ai, bj - aj);
        double limit = d * ellipseFactor + 4;
        PriorityQueue<Node> open = new PriorityQueue<>((p, q) -> p.f != q.f ? Long.compare(p.f, q.f)
            : p.g != q.g ? Long.compare(q.g, p.g) : Long.compare(p.state, q.state));
        Long2LongOpenHashMap best = new Long2LongOpenHashMap();
        best.defaultReturnValue(Long.MAX_VALUE);
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        Long2ObjectOpenHashMap<TerrainSampler.Sample> samples = new Long2ObjectOpenHashMap<>();
        for (int h = 0; h < 8; h++) {
            long s = state(ai, aj, h);
            best.put(s, 0);
            open.add(new Node(s, 0, heuristic(ai, aj, bi, bj)));
        }
        int expansions = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.g != best.get(n.state)) continue;
            int i = si(n.state), j = sj(n.state), h = sh(n.state);
            if (i == bi && j == bj) return new Result(rebuild(n.state, parent, ai, aj), n.g, expansions);
            if (++expansions > MAX_EXPANSIONS) return null;
            TerrainSampler.Sample here = sample(samples, i, j);
            for (int t = -2; t <= 2; t++) {
                int nh = (h + t) & 7;
                int ni = i + DX[nh], nj = j + DZ[nh];
                if (Math.hypot(ni - ai, nj - aj) + Math.hypot(ni - bi, nj - bj) > limit) continue;
                TerrainSampler.Sample next = sample(samples, ni, nj);
                long step;
                if (trunk.test(cellKey(ni, nj))) {
                    step = Math.round(((nh & 1) == 1 ? CELL * Math.sqrt(2) : CELL) * 10 * (0.6 + (Math.abs(t) == 0 ? 0 : Math.abs(t) == 1 ? 0.15 : 0.8)));
                } else {
                    // the cells either side of the one we move into: a road along a steep flank has to cut into it
                    int ph = (nh + 2) & 7;
                    TerrainSampler.Sample left = sample(samples, ni + DX[ph], nj + DZ[ph]), right = sample(samples, ni - DX[ph], nj - DZ[ph]);
                    step = stepCost(here, next, (nh & 1) == 1, Math.abs(t), left, right, (ph & 1) == 1);
                }
                if (step < 0) continue;
                long g = n.g + step;
                long ns = state(ni, nj, nh);
                if (g >= best.get(ns)) continue;
                best.put(ns, g);
                parent.put(ns, n.state);
                open.add(new Node(ns, g, g + heuristic(ni, nj, bi, bj)));
            }
        }
        return null;
    }

    private TerrainSampler.Sample sample(Long2ObjectOpenHashMap<TerrainSampler.Sample> cache, int i, int j) {
        long k = ((long) i << 32) ^ (j & 0xffffffffL);
        TerrainSampler.Sample s = cache.get(k);
        if (s == null) {
            s = terrain.sample(i * CELL + CELL / 2, j * CELL + CELL / 2);
            cache.put(k, s);
        }
        return s;
    }

    private static long heuristic(int i, int j, int bi, int bj) {
        return Math.round(Math.hypot(bi - i, bj - j) * CELL * 10);
    }

    /**
     * Side-hill cost. sideGrade: rise across the move between the cells either side (blocks per block); a road along a
     * slope steeper than FLANK_FREE must cut into it. flank: how far the higher side stands above the road cell. Both are
     * dry ground only (a lake beside the road is not a hill). Tuned so a mountain flank costs about as much as climbing a
     * 0.3 grade, and a road prefers the flat ground at the mountain's foot.
     */
    static final double FLANK_FREE = 0.2, FLANK_W = 40, WALL_FREE = 10, WALL_W = 0.12;

    static double flankFactor(int yb, TerrainSampler.Sample left, TerrainSampler.Sample right, boolean perpDiagonal) {
        if (left.wet() || right.wet()) return 0;
        double span = 2 * CELL * (perpDiagonal ? Math.sqrt(2) : 1);
        double sideGrade = Math.abs(left.ground() - right.ground()) / span;
        double over = Math.max(0, sideGrade - FLANK_FREE);
        int wall = Math.max(left.ground(), right.ground()) - yb;
        return FLANK_W * over * over + WALL_W * Math.max(0, wall - WALL_FREE);
    }

    /** Integer cost of one move, -1 when impassable. Base 10 per block. */
    private long stepCost(TerrainSampler.Sample a, TerrainSampler.Sample b, boolean diagonal, int turn,
                          TerrainSampler.Sample left, TerrainSampler.Sample right, boolean perpDiagonal) {
        double len = diagonal ? CELL * Math.sqrt(2) : CELL;
        if (b.ocean() && b.wet()) return -1;
        if (b.wet() && b.depth() > 10) return -1;
        int ya = a.wet() ? a.water() + 1 : a.ground();
        int yb = b.wet() ? b.water() + 1 : b.ground();
        double grade = Math.abs(yb - ya) / len;
        double factor = 1 + 8 * grade + 60 * grade * grade;
        if (b.wet()) factor += b.river() ? 6 : 10 + b.depth();
        else factor += flankFactor(yb, left, right, perpDiagonal);
        int top = terrain.maxY() - 12;
        if (yb > top) return -1;
        if (yb > top - 40) factor += (yb - (top - 40)) * 0.25;
        double turnCost = turn == 0 ? 0 : turn == 1 ? 0.15 : 0.8;
        return Math.round(len * 10 * (factor + turnCost));
    }

    private static List<int[]> rebuild(long end, Long2LongOpenHashMap parent, int ai, int aj) {
        List<int[]> out = new ArrayList<>();
        long s = end;
        while (true) {
            out.add(new int[]{si(s) * CELL + CELL / 2, sj(s) * CELL + CELL / 2});
            if (!parent.containsKey(s)) break;
            s = parent.get(s);
        }
        Collections.reverse(out);
        return out;
    }
}
