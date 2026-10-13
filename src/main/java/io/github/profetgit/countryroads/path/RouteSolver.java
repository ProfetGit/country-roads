package io.github.profetgit.countryroads.path;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.ChunkPos;

/**
 * The route search of a survey, run on a worker over captured columns (no level access). One call finds one leg: from
 * a start column to a waypoint or to the nearest road column of a goal set, as A* with the straight-line distance to
 * the goals as its estimate. Moves and costs are the house path's own (see PathSearch for the rules): four-connected,
 * corners on level ground, climbing in a row is dear, trees cost, straight bores through hills.
 *
 * Arrays cover the survey window, so a step costs no allocation. A column whose chunk is not captured yet blocks its
 * edge; when the cheapest work left waits on such a chunk the host is asked for it (and the call blocks until the
 * server thread captured it), so the route found is the one a search over the whole loaded world would find.
 */
final class RouteSolver {
    static final int RADIUS = 320, MAX_EXPANSIONS = 1_200_000;
    static final int TURN = 1, END_STEP = 12, START = 4, BEND_ON_SLOPE = 200, WOBBLE = 18, JITTER = 5;
    /** The path is a bed three wide: per block of cut or fill the columns either side need, and for a side that cannot be bed. */
    static final int SIDE = 4, SIDE_CLOSED = 12;
    /** Both sides closed (a trunk and deep water, two trunks): the path would be one wide there. */
    static final int BOTH_CLOSED = 120;
    /** A bend on the water: bridges cross straight. */
    static final int WET_TURN = 80;
    /**
     * A switchback whose legs pass within two columns of each other shares its bed columns (one leg two wide, or one
     * wide when they sit at different heights): per column that close to the route's own earlier stretch. Five cells
     * back is the nearest an unfolded line comes within two (a diagonal staircase is two off after four).
     */
    static final int CLOSE_LEG = 40, CLOSE_BACK_MIN = 5, CLOSE_BACK_MAX = 400;
    static final int MAX_VIAS = 40;
    /** Predicted goals are road centrelines; the painted road is wider, so the estimate gives this many blocks back. */
    static final int GOAL_SLACK = 3;
    /** Open columns this much dearer than the one being expanded have their chunks fetched in the same round trip. */
    static final int LOOKAHEAD = 300;
    /** What a bore costs per column and to start one; the dev command borecost changes them to force tunnels in tests. */
    static volatile int borePer = 26, boreFixed = 80;
    /** Weight of the estimate (1 = shortest route guaranteed; 0 = plain Dijkstra, for comparing with the old search). */
    static volatile double weight = 1.0;
    static final int[] DX = HousePaths.DX, DZ = HousePaths.DZ;
    private static final int INF = Integer.MAX_VALUE;

    /** What the solver needs from the survey: chunks captured, and whether to stop. */
    interface Host {
        /**
         * Blocks until these chunks are captured (or given up, then they read as closed). Without load, chunks that are
         * not loaded are not fetched: they come back (absent) and read as closed for this search.
         */
        long[] need(long[] chunks, boolean load) throws InterruptedException;

        /** Asks for chunks the search will probably reach soon, without waiting; without load only loaded ones are captured. */
        void prefetch(long[] chunks, boolean load);

        boolean cancelled();
    }

    /** A bore of a found route: entry column, direction, walking level, length in columns. */
    record Bore(int x, int z, int d, int y, int len) {
        long id() {
            return ChunkPos.pack(x, z) * 4 + d;
        }
    }

    /** One leg: cells door end first ({column, tunnel index, tunnel length}), the end column and the move into it, its cost, its bores. */
    record Route(List<long[]> part, long endKey, int endDir, long cost, List<Bore> bores) {}

    /** Where a leg ends: one column (a waypoint), or a road column near the goals a distance field was built from. */
    static final class Goal {
        final long target;
        final int tx, tz;
        final int[] dist;
        final int near;

        private Goal(long target, int[] dist, int near) {
            this.target = target;
            this.tx = target == Long.MIN_VALUE ? 0 : ChunkPos.getX(target);
            this.tz = target == Long.MIN_VALUE ? 0 : ChunkPos.getZ(target);
            this.dist = dist;
            this.near = near;
        }

        static Goal column(long key) {
            return new Goal(key, null, 0);
        }

        /** Any open road column; with near >= 0 only those within near blocks of the field's goals (one cluster). */
        static Goal roads(int[] dist, int near) {
            return new Goal(Long.MIN_VALUE, dist, near);
        }

        boolean isColumn() {
            return dist == null;
        }
    }

    final ColumnSnapshot.View t;
    final Host host;
    final int sx, sz, x0, z0, w, doorIdx, borePerUsed, boreFixedUsed;
    private final int hMin;
    private final int[] cost, parent, tree;
    private final byte[] arrive;
    private final short[] bore;
    private int[] hf = new int[1 << 12], hg = new int[1 << 12], hi = new int[1 << 12];
    private long[] hk = new long[1 << 12];
    private int size;
    private final LongOpenHashSet missing = new LongOpenHashSet();
    /** Chunks this search may not fetch (not loaded, and it only uses loaded ground): closed. */
    private final LongOpenHashSet absent = new LongOpenHashSet();
    private boolean load;
    /** Chunks the search may use (the coarse corridor and what was loaded anyway), or null for all. */
    LongSet allowed;
    int expansions;

    // the leg being solved
    private int startIdx, inLevel;
    private boolean[] used;
    private Goal goal;
    private boolean bores;
    private LongSet forbidden;
    private int[] penalty;
    private List<long[]> prefix = List.of();
    /** Cells of the last routeCost'ed chain that ran within two of its own earlier stretch (closeLeg). */
    int lastFolds;
    long firstFold;

    RouteSolver(ColumnSnapshot.View t, Host host, int sx, int sz) {
        this.t = t;
        this.host = host;
        this.sx = sx;
        this.sz = sz;
        this.x0 = t.x0;
        this.z0 = t.z0;
        this.w = t.w;
        this.doorIdx = idx(sx, sz);
        this.borePerUsed = borePer;
        this.boreFixedUsed = boreFixed;
        this.hMin = Math.min(10, borePerUsed);
        int n = w * w;
        cost = new int[n];
        parent = new int[n];
        tree = new int[n];
        arrive = new byte[n];
        bore = new short[n];
        Arrays.fill(tree, Terrain.UNKNOWN);
    }

    int idx(int x, int z) {
        return (x - x0) * w + (z - z0);
    }

    boolean inWindow(int x, int z) {
        return x >= x0 && x < x0 + w && z >= z0 && z < z0 + w;
    }

    int ix(int idx) {
        return idx / w + x0;
    }

    int iz(int idx) {
        return idx % w + z0;
    }

    boolean[] newUsed() {
        return new boolean[w * w];
    }

    static long key(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    private int keyIdx(long k) {
        return idx(ChunkPos.getX(k), ChunkPos.getZ(k));
    }

    /** Multi-source distance (four-connected, obstacles ignored) from the goal columns over the window: the estimate. */
    int[] distanceField(Collection<Long> goals) {
        int n = w * w;
        int[] d = new int[n];
        Arrays.fill(d, INF);
        int[] q = new int[n];
        int head = 0, tail = 0;
        for (long g : goals) {
            int x = ChunkPos.getX(g), z = ChunkPos.getZ(g);
            if (x < x0 || z < z0 || x >= x0 + w || z >= z0 + w) continue;
            int i = idx(x, z);
            if (d[i] == 0) continue;
            d[i] = 0;
            q[tail++] = i;
        }
        while (head < tail) {
            int i = q[head++], x = i / w, z = i % w, nd = d[i] + 1;
            if (x > 0 && d[i - w] == INF) { d[i - w] = nd; q[tail++] = i - w; }
            if (x < w - 1 && d[i + w] == INF) { d[i + w] = nd; q[tail++] = i + w; }
            if (z > 0 && d[i - 1] == INF) { d[i - 1] = nd; q[tail++] = i - 1; }
            if (z < w - 1 && d[i + 1] == INF) { d[i + 1] = nd; q[tail++] = i + 1; }
        }
        return d;
    }

    private int h(int i) {
        double wgt = weight;
        if (wgt <= 0 || hMin <= 0) return 0;
        int d;
        if (goal.isColumn()) d = Math.abs(ix(i) - goal.tx) + Math.abs(iz(i) - goal.tz);
        else {
            int f = goal.dist[i];
            if (f == INF) return 0;
            d = Math.max(0, f - GOAL_SLACK);
        }
        return (int) (wgt * hMin * d);
    }

    /**
     * One leg from start (entered by inDir, the cell before it at inLevel). used: columns earlier legs took (not entered).
     * bores: tunnels allowed; forbidden: bores (Bore.id) known to be unsafe; penalty: extra cost per column, or null.
     * null when there is no route.
     */
    Route solve(long start, int inDir, int inLevel, boolean[] used, Goal goal, boolean bores, LongSet forbidden, int[] penalty) throws InterruptedException {
        return solve(start, inDir, inLevel, used, goal, bores, forbidden, penalty, true);
    }

    /** load false: the search stays on ground already loaded (candidates that may well lose generate no chunks). */
    Route solve(long start, int inDir, int inLevel, boolean[] used, Goal goal, boolean bores, LongSet forbidden, int[] penalty, boolean load) throws InterruptedException {
        this.load = load;
        absent.clear();
        this.startIdx = keyIdx(start);
        this.inLevel = inLevel;
        this.used = used;
        this.goal = goal;
        this.bores = bores && borePerUsed < 1000;
        this.forbidden = forbidden;
        this.penalty = penalty;
        Arrays.fill(cost, INF);
        Arrays.fill(parent, -1);
        Arrays.fill(bore, (short) 0);
        size = 0;
        missing.clear();
        expansions = 0;
        cost[startIdx] = 0;
        arrive[startIdx] = (byte) inDir;
        push(h(startIdx), 0, startIdx);
        int pops = 0;
        while (true) {
            if (size == 0) return null;
            if ((++pops & 1023) == 0 && host.cancelled()) throw new CancellationException();
            int g = hg[0], i = hi[0];
            if (g != cost[i]) {
                pop();
                continue;
            }
            int x = ix(i), z = iz(i), d0 = arrive[i];
            if (reached(i, x, z)) return route(i);
            if (!prepared(x, z, d0)) {
                // a column this expansion reads is not captured: fetch it (with what the search will read next), then
                // expand the same column, so the route is the one a fully loaded world gives
                fetch(hf[0]);
                continue;
            }
            pop();
            if (++expansions > MAX_EXPANSIONS) return null;
            for (int d = 0; d < 4; d++)
                if (d0 == START || d != ((d0 + 2) & 3)) expand(i, x, z, d0, g, d);
        }
    }

    private boolean reached(int i, int x, int z) {
        if (goal.isColumn()) return i == keyIdx(goal.target);
        if (i == doorIdx || !t.openRoad(x, z)) return false;
        return goal.near < 0 || goal.dist[i] <= goal.near;
    }

    private Route route(int end) {
        boolean includeEnd = goal.isColumn();
        List<long[]> part = extract(end, includeEnd);
        List<Bore> bs = new ArrayList<>();
        for (int n = end; n != startIdx; n = parent[n])
            if (bore[n] > 0) {
                int p = parent[n];
                bs.add(new Bore(ix(p), iz(p), arrive[n], Terrain.level(t.ground(ix(p), iz(p))), bore[n]));
            }
        return new Route(part, key(ix(end), iz(end)), arrive[end], cost[end], bs);
    }

    /** The cells of the leg just found, start end first (the start column itself not included). */
    private List<long[]> extract(int endIdx, boolean includeEnd) {
        List<long[]> rev = new ArrayList<>();
        int n = endIdx;
        while (n != startIdx) {
            int p = parent[n];
            if (n != endIdx || includeEnd) rev.add(new long[]{key(ix(n), iz(n)), 0, 0});
            int len = bore[n];
            if (len > 0) {
                int d = arrive[n], px = ix(p), pz = iz(p);
                for (int m = len - 1; m >= 1; m--) rev.add(new long[]{key(px + DX[d] * m, pz + DZ[d] * m), m, len});
            }
            n = p;
        }
        java.util.Collections.reverse(rev);
        return rev;
    }

    /** Whether every chunk the expansion of (x, z) reads is captured (or may not be fetched); adds the missing ones. */
    private boolean prepared(int x, int z, int d0) {
        boolean ok = true;
        for (int d = 0; d < 4; d++) {
            if (d0 != START && d == ((d0 + 2) & 3)) continue;
            int nx = x + DX[d], nz = z + DZ[d];
            if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > RADIUS) continue;
            int ni = idx(nx, nz);
            if (ni == doorIdx || used[ni]) continue;
            if (!ready(nx, nz)) {
                ok = false;
                continue;
            }
            if (!bores) continue;
            int ny = ground(nx, nz), from = ground(x, z), walkFrom = Terrain.level(from);
            if (Terrain.usable(ny) && ny < Terrain.WET && from < Terrain.WET && ny - walkFrom >= 1 && !boreLineReady(x, z, d, walkFrom)) ok = false;
        }
        return ok;
    }

    /** The hill ahead of a possible bore is captured as far as tryBore reads it; adds the next missing chunk. */
    private boolean boreLineReady(int x, int z, int d, int y) {
        int roofed = 0;
        for (int m = 1; m <= Tunnels.MAX_LEN; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            if (!t.known(cx, cz)) {
                long ch = ChunkPos.pack(cx >> 4, cz >> 4);
                if (off(ch) || Math.max(Math.abs(cx - sx), Math.abs(cz - sz)) > RADIUS) return true;
                missing.add(ch);
                return false;
            }
            int g = ground(cx, cz);
            if (!Terrain.usable(g) || g >= Terrain.WET || g < y - 1) return true;
            if (g <= y + 1) {
                if (roofed >= Tunnels.MIN_ROOFED) return sidesReady(x, z, d, m);
                if (roofed > 0 || m > 10) return true;
            } else if (g >= y + Tunnels.ROOF) roofed++;
            else if (roofed == 0 && m > 10) return true;
        }
        return true;
    }

    /** The chunks of the columns two either side of a bore (its rock is read there) are captured; adds the missing ones. */
    private boolean sidesReady(int x, int z, int d, int exit) {
        boolean ok = true;
        int rx = -DZ[d], rz = DX[d];
        for (int m = 1; m < exit; m++)
            for (int l = -2; l <= 2; l += 4) {
                int qx = x + DX[d] * m + l * rx, qz = z + DZ[d] * m + l * rz;
                long ch = ChunkPos.pack(qx >> 4, qz >> 4);
                if (!t.known(qx, qz) && !off(ch)) {
                    missing.add(ch);
                    ok = false;
                }
            }
        return ok;
    }

    /**
     * Fetches the missing chunks, together with those the open columns just behind in the queue will read (cost within
     * LOOKAHEAD of the first): one round trip for a stretch of frontier instead of one per column. Chunks around them
     * come along: loaded ones are captured at once, and the neighbours on the goal side are fetched, as the search
     * will most likely go there next.
     */
    private void fetch(int f) throws InterruptedException {
        for (int e = 1, scanned = 0; e < size && scanned < 4096; e++) {
            if (hf[e] > f + LOOKAHEAD || hg[e] != cost[hi[e]]) continue;
            scanned++;
            int i = hi[e];
            prepared(ix(i), iz(i), arrive[i]);
        }
        long[] want = missing.toLongArray();
        LongOpenHashSet around = new LongOpenHashSet(), ahead = new LongOpenHashSet();
        for (long c : want) {
            int mx = ChunkPos.getX(c), mz = ChunkPos.getZ(c);
            int here = chunkDist(mx, mz);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int cx = mx + dx, cz = mz + dz;
                    if (Math.max(Math.abs((cx << 4) + 8 - sx), Math.abs((cz << 4) + 8 - sz)) > RADIUS + 16 || t.snap.get(cx, cz) != null) continue;
                    long k = ChunkPos.pack(cx, cz);
                    if (missing.contains(k) || off(k)) continue;
                    if (load && Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && chunkDist(cx, cz) <= here) ahead.add(k);
                    else around.add(k);
                }
        }
        host.prefetch(around.toLongArray(), false);
        if (!ahead.isEmpty()) host.prefetch(ahead.toLongArray(), true);
        for (long a : host.need(want, load)) absent.add(a);
        missing.clear();
    }

    /** Captures the chunks within r blocks of a route's cells (those this search may use), so what is read beside it is known. */
    void ensureAround(List<long[]> chain, int r, boolean load) throws InterruptedException {
        LongOpenHashSet want = new LongOpenHashSet();
        for (long[] e : chain) {
            int x = ChunkPos.getX(e[0]), z = ChunkPos.getZ(e[0]);
            for (int dx = -r; dx <= r; dx += r)
                for (int dz = -r; dz <= r; dz += r) {
                    int cx = (x + dx) >> 4, cz = (z + dz) >> 4;
                    long ch = ChunkPos.pack(cx, cz);
                    if (t.snap.get(cx, cz) == null && (allowed == null || allowed.contains(ch))) want.add(ch);
                }
        }
        if (!want.isEmpty()) host.need(want.toLongArray(), load);
    }

    /** How far a chunk's centre is from the goal (the estimate's distance), for choosing which chunks to fetch ahead. */
    private int chunkDist(int cx, int cz) {
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        if (goal.isColumn()) return Math.abs(x - goal.tx) + Math.abs(z - goal.tz);
        x = Math.max(x0, Math.min(x0 + w - 1, x));
        z = Math.max(z0, Math.min(z0 + w - 1, z));
        return goal.dist[idx(x, z)];
    }

    /** Whether every chunk this column's cost needs (its own, and its neighbours' for trunks beside it) is captured; adds the missing ones. */
    private boolean ready(int x, int z) {
        boolean ok = true;
        if (!t.known(x, z) && !off(ChunkPos.pack(x >> 4, z >> 4))) {
            missing.add(ChunkPos.pack(x >> 4, z >> 4));
            ok = false;
        }
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d], nz = z + DZ[d];
            if ((nx >> 4) == (x >> 4) && (nz >> 4) == (z >> 4)) continue;
            if (t.inside(nx, nz) && !t.known(nx, nz) && !off(ChunkPos.pack(nx >> 4, nz >> 4))) {
                missing.add(ChunkPos.pack(nx >> 4, nz >> 4));
                ok = false;
            }
        }
        return ok;
    }

    /** A chunk this search does not use: not to be fetched, its columns closed. */
    private boolean off(long ch) {
        return absent.contains(ch) || (allowed != null && !allowed.contains(ch));
    }

    private boolean usable(int x, int z) {
        return t.known(x, z) && (allowed == null || allowed.contains(ChunkPos.pack(x >> 4, z >> 4)));
    }

    private int ground(int x, int z) {
        return t.ground(x, z);
    }

    private int treeCost(int i, int x, int z, int walkY) {
        int v = tree[i];
        if (v == Terrain.UNKNOWN) tree[i] = v = t.treeCost(x, z, walkY);
        return v;
    }

    private int prevLevel(int k) {
        int p = parent[k];
        return p < 0 ? inLevel : Terrain.level(ground(ix(p), iz(p)));
    }

    private void expand(int k, int x, int z, int d0, int c, int d) {
        int nx = x + DX[d], nz = z + DZ[d];
        if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > RADIUS) return;
        int ni = idx(nx, nz);
        if (ni == doorIdx || used[ni]) return;
        if (!usable(nx, nz)) return;
        int ny = ground(nx, nz);
        int from = ground(x, z);
        int walkFrom = Terrain.level(from);
        boolean fromWet = from >= Terrain.WET;
        if (bores && Terrain.usable(ny) && ny < Terrain.WET && !fromWet && ny - walkFrom >= 1) tryBore(k, x, z, d0, c, d, walkFrom);
        if (!Terrain.usable(ny)) return;
        boolean wet = ny >= Terrain.WET;
        int walkTo = Terrain.level(ny);
        int dy = walkTo - walkFrom;
        if (Math.abs(dy) > 1) return;
        int bend = 0;
        if (d0 != START && d0 != d) {
            // a corner belongs on level ground: a stair at a bend would end in a wall
            if (prevLevel(k) != walkFrom || walkTo != walkFrom) bend = BEND_ON_SLOPE;
        }
        int add = 10 + bend + (int) (lattice(31, nx * 7 + d, nz) * JITTER) + (dy != 0 ? climbPenalty(k) : 0) + treeCost(ni, nx, nz, walkTo)
            + wobble(nx, nz) + (d0 != START && d0 != d ? TURN : 0) + 8 * Math.abs(dy) + (wet ? 30 : 0) + sideCost(nx, nz, d, walkTo)
            + closeLeg(k, nx, nz) + (d0 != START && d0 != d && (wet || fromWet) ? WET_TURN : 0);
        if (dy < 0 && x == sx && z == sz) add += END_STEP;
        else if (dy > 0 && t.openRoad(nx, nz)) add += END_STEP;
        if (penalty != null) add += penalty[ni];
        int total = c + add;
        if (total >= cost[ni]) return;
        cost[ni] = total;
        parent[ni] = k;
        arrive[ni] = (byte) d;
        bore[ni] = 0;
        push(total + h(ni), total, ni);
    }

    /** What the bed beside a step into (x, z) heading d costs: cut or fill of the two side columns, or a side that cannot be bed. */
    private int sideCost(int x, int z, int d, int y) {
        int rx = -DZ[d], rz = DX[d], c = 0, closed = 0;
        for (int s = -1; s <= 1; s += 2) {
            int g = ground(x + s * rx, z + s * rz);
            if (Terrain.deep(g) && g - Terrain.DEEP + 1 == y) c += SIDE;
            else if (!Terrain.usable(g)) {
                c += SIDE_CLOSED;
                closed++;
            }
            else if (g < Terrain.WET) {
                int off = Math.abs(Terrain.level(g) - y);
                c += off > PathPlan.BED_MAX ? SIDE_CLOSED : SIDE * off;
            }
        }
        return closed == 2 ? c + BOTH_CLOSED : c;
    }

    /**
     * CLOSE_LEG when (x, z) is within two columns of the route's own cell CLOSE_BACK_MIN..CLOSE_BACK_MAX cells back
     * (the cells inside a bore count one by one: a stair climbing out beside its own tunnel is a close leg too).
     */
    private int closeLeg(int k, int x, int z) {
        return closeLeg(k, x, z, 1);
    }

    /** closeLeg for a column `first` cells past k (the cells of a bore being dug from k). */
    private int closeLeg(int k, int x, int z, int first) {
        int back = first;
        for (int a = k; a >= 0; a = parent[a]) {
            if (back > CLOSE_BACK_MAX) return 0;
            if (back >= CLOSE_BACK_MIN && Math.abs(ix(a) - x) <= 2 && Math.abs(iz(a) - z) <= 2) return CLOSE_LEG;
            int len = bore[a];
            for (int m = 1; m < len; m++)
                if (back + m >= CLOSE_BACK_MIN && Math.abs(ix(a) - DX[arrive[a]] * m - x) <= 2 && Math.abs(iz(a) - DZ[arrive[a]] * m - z) <= 2) return CLOSE_LEG;
            back += Math.max(1, len);
        }
        // the legs before this one (their last cell is this leg's start, already counted)
        for (int i = prefix.size() - 2; i >= 0 && back <= CLOSE_BACK_MAX; i--, back++) {
            long c = prefix.get(i)[0];
            if (back >= CLOSE_BACK_MIN && Math.abs(ChunkPos.getX(c) - x) <= 2 && Math.abs(ChunkPos.getZ(c) - z) <= 2) return CLOSE_LEG;
        }
        return 0;
    }

    /** Cells of the route's earlier legs (door end first), or null: closeLeg looks back through them too. */
    void prefix(List<long[]> cells) {
        prefix = cells == null ? List.of() : cells;
    }

    /**
     * A straight tunnel at the walked level through the hill ahead: columns whose ground is at least ROOF above the
     * path get a roof; the bore ends where the ground comes back down to the level. Needs MIN_ROOFED roofed columns.
     * Whether the rock is safe to cut (Tunnels.safe) is checked later, on the server thread, only for the bores of the
     * routes found; an unsafe one is forbidden and the leg searched again.
     */
    private void tryBore(int k, int x, int z, int d0, int c, int d, int y) {
        int exit = -1, roofed = 0;
        for (int m = 1; m <= Tunnels.MAX_LEN; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            if (!usable(cx, cz)) return;
            int g = ground(cx, cz);
            if (!Terrain.usable(g) || g >= Terrain.WET || g < y - 1) return;
            if (g <= y + 1) {
                if (roofed >= Tunnels.MIN_ROOFED) {
                    exit = m;
                    break;
                }
                if (roofed > 0 || m > 10) return;
            } else if (g >= y + Tunnels.ROOF) roofed++;
            else if (roofed == 0 && m > 10) return;
        }
        if (exit < 0) return;
        if (forbidden != null && forbidden.contains(key(x, z) * 4 + d)) return;
        int ex = x + DX[d] * exit, ez = z + DZ[d] * exit;
        if (Math.max(Math.abs(ex - sx), Math.abs(ez - sz)) > RADIUS) return;
        int ei = idx(ex, ez);
        if (used[ei] || ei == doorIdx) return;
        for (int m = 1; m < exit; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            if (used[idx(cx, cz)]) return;
        }
        if (!safe(x, z, d, y, exit)) return;
        // a bore must not run under (or through) the stretch that led here
        for (int a = k; a >= 0; a = parent[a]) {
            int ax = ix(a), az = iz(a);
            if (d == 0 || d == 2 ? ax == x && Integer.signum(az - z) == DZ[d] && Math.abs(az - z) <= exit
                : az == z && Integer.signum(ax - x) == DX[d] && Math.abs(ax - x) <= exit) return;
            if (a == startIdx) break;
        }
        int bend = d0 != START && d0 != d ? (prevLevel(k) != y ? BEND_ON_SLOPE : TURN) : 0;
        int close = 0;
        for (int m = 1; m <= exit; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            close += closeLeg(k, cx, cz, m) + (penalty != null ? penalty[idx(cx, cz)] : 0);
        }
        int total = c + bend + borePerUsed * exit + boreFixedUsed + close;
        if (total >= cost[ei]) return;
        cost[ei] = total;
        parent[ei] = k;
        arrive[ei] = (byte) d;
        bore[ei] = (short) exit;
        push(total + h(ei), total, ei);
    }

    /** Bores whose rock was read: id -> 1 safe, 0 not (the rock does not change while the survey runs). */
    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap safeMemo = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    /**
     * Tunnels.safe on the worker: no fluid within a block of the walls and roof, nothing built, no trees, no road. The
     * rock is read straight from the captured chunks (reads only; a chunk edited meanwhile at worst gives a stale answer,
     * and the chosen route's bores are checked again on the server thread before it is shown).
     */
    boolean safe(int x, int z, int d, int y, int len) {
        long id = key(x, z) * 4 + d;
        byte v = safeMemo.getOrDefault(id, (byte) -1);
        if (v >= 0) return v == 1;
        boolean ok = readSafe(x, z, d, y, len);
        safeMemo.put(id, (byte) (ok ? 1 : 0));
        return ok;
    }

    private boolean readSafe(int x, int z, int d, int y, int len) {
        if (y - 2 <= t.minY + 4) return false;
        int rx = -DZ[d], rz = DX[d];
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
        try {
            for (int m = 1; m < len; m++) {
                int cx = x + DX[d] * m, cz = z + DZ[d] * m;
                if (t.road(cx, cz)) return false;
                for (int l = -2; l <= 2; l++) {
                    int qx = cx + l * rx, qz = cz + l * rz;
                    net.minecraft.world.level.chunk.LevelChunk c = t.snap.live.get(ChunkPos.pack(qx >> 4, qz >> 4));
                    if (c == null) return false;
                    for (int yy = y - 1; yy <= y + 5; yy++) {
                        net.minecraft.world.level.block.state.BlockState s = c.getBlockState(p.set(qx, yy, qz));
                        if (!s.getFluidState().isEmpty() || io.github.profetgit.countryroads.retro.Retrofit.isCrafted(s)) return false;
                        if (Math.abs(l) <= 1 && yy > y && yy <= y + Tunnels.ROOF && (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES)))
                            return false;
                    }
                }
            }
        } catch (RuntimeException e) {
            // the server thread changed a palette while it was read: call it unsafe
            return false;
        }
        return true;
    }

    /** Climbing several of the last few cells in a row is dear: on a slope the route zigzags across it with flat stretches between the rises. */
    private int climbPenalty(int k) {
        int cnt = 0;
        int a = k;
        for (int q = 0; q < 4; q++) {
            int pa = parent[a];
            if (pa < 0) break;
            if (Terrain.level(ground(ix(a), iz(a))) != Terrain.level(ground(ix(pa), iz(pa)))) cnt++;
            a = pa;
        }
        return 6 * cnt;
    }

    // ---- binary heap ordered by (f, column key): the same order as the old search when the estimate is 0

    private void push(int f, int g, int i) {
        if (size == hf.length) {
            int n = size * 2;
            hf = Arrays.copyOf(hf, n);
            hg = Arrays.copyOf(hg, n);
            hi = Arrays.copyOf(hi, n);
            hk = Arrays.copyOf(hk, n);
        }
        long k = key(ix(i), iz(i));
        int c = size++;
        while (c > 0) {
            int p = (c - 1) >> 1;
            if (hf[p] < f || (hf[p] == f && hk[p] <= k)) break;
            hf[c] = hf[p];
            hg[c] = hg[p];
            hi[c] = hi[p];
            hk[c] = hk[p];
            c = p;
        }
        hf[c] = f;
        hg[c] = g;
        hi[c] = i;
        hk[c] = k;
    }

    private void pop() {
        int last = --size;
        if (last == 0) return;
        int f = hf[last], g = hg[last], i = hi[last];
        long k = hk[last];
        int c = 0;
        while (true) {
            int l = 2 * c + 1;
            if (l >= last) break;
            int r = l + 1;
            int m = r < last && (hf[r] < hf[l] || (hf[r] == hf[l] && hk[r] < hk[l])) ? r : l;
            if (hf[m] > f || (hf[m] == f && hk[m] >= k)) break;
            hf[c] = hf[m];
            hg[c] = hg[m];
            hi[c] = hi[m];
            hk[c] = hk[m];
            c = m;
        }
        hf[c] = f;
        hg[c] = g;
        hi[c] = i;
        hk[c] = k;
    }

    // ---- the cost of a finished route, and the bends of the wandering pass

    /**
     * The search's own cost of a finished route (start end first, as a leg gives it), recomputed along the chain:
     * candidates are compared by it. Path-dependent terms restart at the door only, so legs make it approximate.
     */
    long routeCost(List<long[]> chain, long endKey) {
        lastFolds = 0;
        List<long[]> cells = new ArrayList<>(chain);
        if (cells.isEmpty() || cells.get(cells.size() - 1)[0] != endKey) cells.add(new long[]{endKey, 0, 0});
        List<Long> nodes = new ArrayList<>();
        List<Integer> boreLen = new ArrayList<>();
        nodes.add(key(sx, sz));
        boreLen.add(0);
        for (int i = 0; i < cells.size(); i++) {
            long[] e = cells.get(i);
            if (e[1] > 0) {
                int len = (int) e[2];
                i += len - 1 - (int) e[1];
                if (i + 1 < cells.size()) {
                    nodes.add(cells.get(i + 1)[0]);
                    boreLen.add(len);
                    i++;
                }
                continue;
            }
            nodes.add(e[0]);
            boreLen.add(0);
        }
        int n = nodes.size();
        int[] lv = new int[n];
        for (int j = 0; j < n; j++) lv[j] = Terrain.level(ground(ChunkPos.getX(nodes.get(j)), ChunkPos.getZ(nodes.get(j))));
        long total = 0;
        int d0 = START;
        for (int j = 1; j < n; j++) {
            int x = ChunkPos.getX(nodes.get(j - 1)), z = ChunkPos.getZ(nodes.get(j - 1));
            int nx = ChunkPos.getX(nodes.get(j)), nz = ChunkPos.getZ(nodes.get(j));
            int d = PathPlan.dirOf(Integer.signum(nx - x), Integer.signum(nz - z));
            int prev = j >= 2 ? lv[j - 2] : 0;
            if (boreLen.get(j) > 0) {
                int y = lv[j - 1];
                total += (d0 != START && d0 != d ? (prev != y ? BEND_ON_SLOPE : TURN) : 0) + (long) borePerUsed * boreLen.get(j) + boreFixedUsed;
                for (int m = 1; m <= boreLen.get(j); m++) {
                    int cl = closeLeg(nodes, boreLen, j - 1, x + DX[d] * m, z + DZ[d] * m, m);
                    total += cl;
                    if (cl > 0 && lastFolds++ == 0) firstFold = key(x + DX[d] * m, z + DZ[d] * m);
                }
                d0 = d;
                continue;
            }
            int dy = lv[j] - lv[j - 1];
            boolean wet = ground(nx, nz) >= Terrain.WET;
            int bend = d0 != START && d0 != d && (prev != lv[j - 1] || lv[j] != lv[j - 1]) ? BEND_ON_SLOPE : 0;
            int climb = 0;
            if (dy != 0) for (int q = 0; q < 4 && j - 2 - q >= 0; q++) if (lv[j - 1 - q] != lv[j - 2 - q]) climb += 6;
            long add = 10 + bend + (int) (lattice(31, nx * 7 + d, nz) * JITTER) + climb + t.treeCost(nx, nz, lv[j]) + wobble(nx, nz)
                + (d0 != START && d0 != d ? TURN : 0) + 8L * Math.abs(dy) + (wet ? 30 : 0) + sideCost(nx, nz, d, lv[j])
                + (d0 != START && d0 != d && (wet || ground(x, z) >= Terrain.WET) ? WET_TURN : 0);
            int cl = closeLeg(nodes, boreLen, j - 1, nx, nz);
            add += cl;
            if (cl > 0 && lastFolds++ == 0) firstFold = key(nx, nz);
            if (dy < 0 && x == sx && z == sz) add += END_STEP;
            else if (dy > 0 && j == n - 1) add += END_STEP;
            total += add;
            d0 = d;
        }
        return total;
    }

    /** closeLeg over a finished chain of nodes (bore exits carry their bore length): the cost the search charged. */
    private static int closeLeg(List<Long> nodes, List<Integer> boreLen, int k, int x, int z) {
        return closeLeg(nodes, boreLen, k, x, z, 1);
    }

    private static int closeLeg(List<Long> nodes, List<Integer> boreLen, int k, int x, int z, int first) {
        int back = first;
        for (int a = k; a >= 0 && back <= CLOSE_BACK_MAX; a--) {
            int ax = ChunkPos.getX(nodes.get(a)), az = ChunkPos.getZ(nodes.get(a));
            if (back >= CLOSE_BACK_MIN && Math.abs(ax - x) <= 2 && Math.abs(az - z) <= 2) return CLOSE_LEG;
            int len = boreLen.get(a);
            if (len > 0 && a > 0) {
                int px = ChunkPos.getX(nodes.get(a - 1)), pz = ChunkPos.getZ(nodes.get(a - 1));
                int ddx = Integer.signum(ax - px), ddz = Integer.signum(az - pz);
                for (int m = 1; m < len; m++)
                    if (back + m >= CLOSE_BACK_MIN && Math.abs(ax - ddx * m - x) <= 2 && Math.abs(az - ddz * m - z) <= 2) return CLOSE_LEG;
            }
            back += Math.max(1, len);
        }
        return 0;
    }

    /**
     * Pick bends for the direct route: every so often a point a few blocks off the walked line, alternating sides. The
     * second search runs through them in order (and through the player's waypoints), so the trail swings instead of
     * ruling one long line.
     */
    List<Long> meander(List<long[]> chain, List<Long> waypoints, boolean[] used) {
        int n = chain.size();
        List<Long> out = new ArrayList<>();
        if (n < 24) return waypoints.isEmpty() ? out : new ArrayList<>(waypoints);
        java.util.Set<Long> waypointCols = new java.util.HashSet<>(waypoints);
        List<long[]> picks = new ArrayList<>();
        int i = 8 + (int) (lattice(3, sx, sz) * 6);
        int side = lattice(5, sx, sz) < 0.5 ? 1 : -1;
        while (i < n - 8 && picks.size() < MAX_VIAS) {
            long[] e = chain.get(i);
            long col = e[0];
            boolean near = false;
            for (int q = Math.max(0, i - 4); q <= Math.min(n - 1, i + 4); q++) if (chain.get(q)[1] > 0 || waypointCols.contains(chain.get(q)[0])) near = true;
            if (!near) {
                int px = ChunkPos.getX(chain.get(i - 1)[0]), pz = ChunkPos.getZ(chain.get(i - 1)[0]);
                int qx = ChunkPos.getX(chain.get(i + 1)[0]), qz = ChunkPos.getZ(chain.get(i + 1)[0]);
                int dx = Integer.signum(qx - px), dz = Integer.signum(qz - pz);
                if (dx * dz == 0 && dx + dz != 0) {
                    int rx = -dz, rz = dx, x = ChunkPos.getX(col), z = ChunkPos.getZ(col);
                    int off = 3 + (int) (lattice(11, x, z) * 4);
                    int vx = x + side * off * rx, vz = z + side * off * rz;
                    if (viable(vx, vz, Terrain.level(ground(x, z)), used)) {
                        picks.add(new long[]{i, key(vx, vz)});
                        side = -side;
                        i += 16 + (int) (lattice(13, x, z) * 10);
                        continue;
                    }
                }
            }
            i += 3;
        }
        // waypoints stay in order with the bends between them
        List<long[]> all = new ArrayList<>(picks);
        for (int q = 0; q < n; q++) {
            long col = chain.get(q)[0];
            if (waypointCols.contains(col)) all.add(new long[]{q, col});
        }
        all.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] a : all) out.add(a[1]);
        return out;
    }

    /** A bend point must be open ground, no tree, no water, and about as high as the walked line. */
    private boolean viable(int x, int z, int lvl, boolean[] used) {
        if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > RADIUS - 8) return false;
        if (used[idx(x, z)] || t.road(x, z)) return false;
        if (!t.known(x, z)) return false;
        int g = ground(x, z);
        if (!Terrain.usable(g) || g >= Terrain.WET || Math.abs(g - lvl) > 3) return false;
        return t.treeCost(x, z, g) == 0;
    }

    // ---- noise

    static double lattice(long seed, int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ seed;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 65535.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double noise2(int x, int z, double scale, long seed) {
        double fx = x / scale, fz = z / scale;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double a = lattice(seed, x0, z0) * (1 - tx) + lattice(seed, x0 + 1, z0) * tx, b = lattice(seed, x0, z0 + 1) * (1 - tx) + lattice(seed, x0 + 1, z0 + 1) * tx;
        return a * (1 - tz) + b * tz;
    }

    /** Extra route cost 0..WOBBLE from two octaves of smooth noise (about 14 and 5 blocks across). */
    static int wobble(int x, int z) {
        return (int) ((0.65 * noise2(x, z, 14, 7) + 0.35 * noise2(x, z, 5, 19)) * WOBBLE);
    }

    /** Smooth 1D value noise 0..1 along the path, period about 7 cells. */
    static double along(long seed, int i) {
        double f = i / 7.0;
        int i0 = (int) Math.floor(f);
        double t = smooth(f - i0);
        return lattice(seed, i0, 0) * (1 - t) + lattice(seed, i0 + 1, 0) * t;
    }
}
