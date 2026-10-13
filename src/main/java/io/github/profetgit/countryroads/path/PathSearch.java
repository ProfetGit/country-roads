package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.RoadColumns;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The survey of one stake: a time-sliced search over the real terrain from the door to the nearest road. It runs in
 * legs (door, the player's waypoints in order, the road), then once more with bends added to the straight stretches so
 * the trail wanders, falling back to the direct route when the wandering one fails.
 *
 * Moves are four-connected, so every cell touches the next along a side. A corner belongs on level ground (a stair at
 * a bend would end in a wall), climbing several cells in a row is dear (the route zigzags across a slope), trees cost,
 * and a straight bore through a mountain is an extra move when the hill ahead is high enough to roof it.
 */
final class PathSearch {
    static final int RADIUS = 320, MAX_EXPANSIONS = 1_200_000, WAIT_TICKS = 1200, TICKET_EVERY = 100;
    static final int TURN = 1, END_STEP = 12, START = 4, BEND_ON_SLOPE = 200, WOBBLE = 18, JITTER = 5;
    static final int MAX_VIAS = 40;
    /** What a bore costs per column and to start one; the dev command borecost changes them to force tunnels in tests. */
    static int borePer = 26, boreFixed = 80;
    static final int UNKNOWN = Integer.MIN_VALUE, CLOSED = -100000;
    static final int[] DX = HousePaths.DX, DZ = HousePaths.DZ;
    static final Direction[] DIRS = HousePaths.DIRS;

    final ServerLevel level;
    final BlockPos stake;
    final ServerPlayer player;
    final int sx, sz;
    final long doorKey;

    private final PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
    private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap cost = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap trees = new Long2IntOpenHashMap();
    /** Direction each column was entered by (START at the door). */
    private final Long2IntOpenHashMap arrive = new Long2IntOpenHashMap();
    /** Exit column of a bore -> its length in columns; the columns between are tunnel. */
    private final Long2IntOpenHashMap bore = new Long2IntOpenHashMap();
    /** Edges whose far column is in a chunk that is not loaded yet: {lower-bound cost, from column, direction, cost at from}. */
    private final List<long[]> blocked = new ArrayList<>();
    /** Chunks asked for (ticketed again every few seconds while the survey runs). */
    private final Set<Long> requested = new HashSet<>();
    private int waited, age, expansions;

    // legs
    private long startKey;
    private int inDir = START, inLevel;
    private List<Long> targets;
    private int leg, stage;
    private List<long[]> acc = new ArrayList<>(), direct;
    private long directEnd;
    private final Set<Long> used = new HashSet<>();
    private final List<Long> waypoints;

    // outcome
    HousePaths.Survey survey;
    /** Set when the search ended without a route: the translation key of the player's message. */
    String failure;
    int failureArg;

    PathSearch(ServerLevel level, BlockPos stake, ServerPlayer player, List<BlockPos> waypoints) {
        this.level = level;
        this.stake = stake;
        this.player = player;
        this.sx = stake.getX();
        this.sz = stake.getZ();
        this.doorKey = key(sx, sz);
        this.waypoints = new ArrayList<>();
        for (BlockPos w : waypoints) this.waypoints.add(key(w.getX(), w.getZ()));
        cost.defaultReturnValue(Integer.MAX_VALUE);
        ground.defaultReturnValue(UNKNOWN);
        trees.defaultReturnValue(UNKNOWN);
        this.targets = new ArrayList<>(this.waypoints);
        this.startKey = doorKey;
        used.add(doorKey);
        startLeg();
    }

    static long key(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    static int level(int g) {
        return g >= 100000 ? g - 100000 + 1 : g;
    }

    private void startLeg() {
        open.clear();
        parent.clear();
        cost.clear();
        arrive.clear();
        bore.clear();
        blocked.clear();
        expansions = 0;
        waited = 0;
        cost.put(startKey, 0);
        arrive.put(startKey, inDir);
        open.add(new long[]{0, startKey});
    }

    /** One slice of work; true when the search is over (see survey / failure). */
    boolean step(long until) {
        if (age++ % TICKET_EVERY == 0) for (long ch : requested) ticket(ChunkPos.getX(ch), ChunkPos.getZ(ch));
        retryBlocked();
        while (true) {
            long[] n = open.peek();
            if (!blocked.isEmpty() && (n == null || n[0] >= minBlocked())) {
                // the cheapest unexplored edge is waiting for a chunk: do not go on past it
                if (++waited > WAIT_TICKS) {
                    blocked.clear();
                    continue;
                }
                return false;
            }
            if (n == null) break;
            if (System.nanoTime() > until) return false;
            open.poll();
            long k = n[1];
            int c = (int) n[0];
            if (c != cost.get(k)) continue;
            int x = ChunkPos.getX(k), z = ChunkPos.getZ(k), d0 = arrive.get(k);
            boolean viaLeg = leg < targets.size();
            boolean reached = viaLeg ? k == targets.get(leg) : k != doorKey && RoadColumns.isOpenRoad(level, x, z);
            if (reached) return legDone(k, viaLeg);
            if (++expansions > MAX_EXPANSIONS) break;
            for (int d = 0; d < 4; d++)
                if (d0 == START || d != ((d0 + 2) & 3)) expand(k, x, z, d0, c, d);
        }
        return noRoute();
    }

    private boolean noRoute() {
        if (stage == 1) {
            // the wandering route found no way: keep the direct one
            return conclude(direct, directEnd);
        }
        io.github.profetgit.countryroads.CountryRoads.LOG.debug("Survey from {} found no route after {} columns", stake, expansions);
        if (player == null) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STAKE-DEBUG expansions=" + expansions);
        if (leg < targets.size()) {
            failure = "countryroads.stake.no_waypoint";
            failureArg = leg + 1;
        } else {
            failure = "countryroads.stake.no_road";
            failureArg = RADIUS;
        }
        return true;
    }

    private boolean legDone(long k, boolean viaLeg) {
        List<long[]> part = extract(k, viaLeg);
        if (viaLeg) {
            acc.addAll(part);
            for (long[] e : part) used.add(e[0]);
            inDir = arrive.get(k);
            inLevel = part.size() >= 2 ? level(ground(ChunkPos.getX(part.get(part.size() - 2)[0]), ChunkPos.getZ(part.get(part.size() - 2)[0])))
                : level(ground(ChunkPos.getX(startKey), ChunkPos.getZ(startKey)));
            startKey = k;
            leg++;
            startLeg();
            return false;
        }
        acc.addAll(part);
        if (stage == 0) {
            List<Long> vias = meander(acc);
            if (!vias.isEmpty()) {
                direct = new ArrayList<>(acc);
                directEnd = k;
                acc = new ArrayList<>();
                used.clear();
                used.add(doorKey);
                targets = vias;
                leg = 0;
                stage = 1;
                startKey = doorKey;
                inDir = START;
                startLeg();
                return false;
            }
        }
        return conclude(acc, k);
    }

    private boolean conclude(List<long[]> chain, long endKey) {
        survey = PathPlan.build(this, chain, endKey);
        if (survey == null) failure = "countryroads.stake.on_road";
        return true;
    }

    /** The cells of the leg just found, door end first; the end column only for a leg that stops at a waypoint. */
    private List<long[]> extract(long endKey, boolean includeEnd) {
        List<long[]> rev = new ArrayList<>();
        long n = endKey;
        while (n != startKey) {
            long p = parent.get(n);
            if (n != endKey || includeEnd) rev.add(new long[]{n, 0, 0});
            int len = bore.get(n);
            if (len > 0) {
                int d = arrive.get(n);
                int px = ChunkPos.getX(p), pz = ChunkPos.getZ(p);
                for (int m = len - 1; m >= 1; m--) rev.add(new long[]{key(px + DX[d] * m, pz + DZ[d] * m), m, len});
            }
            n = p;
        }
        java.util.Collections.reverse(rev);
        return rev;
    }

    /**
     * Pick bends for the direct route: every so often a point a few blocks off the walked line, alternating sides. The
     * second search runs through them in order (and through the player's waypoints), so the trail swings instead of
     * ruling one long line.
     */
    private List<Long> meander(List<long[]> chain) {
        int n = chain.size();
        List<Long> out = new ArrayList<>();
        if (n < 24) return waypoints.isEmpty() ? out : new ArrayList<>(waypoints);
        Set<Long> waypointCols = new HashSet<>(waypoints);
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
                    if (viable(vx, vz, level(ground(x, z)))) {
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
    private boolean viable(int x, int z, int lvl) {
        if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > RADIUS - 8) return false;
        if (used.contains(key(x, z)) || RoadColumns.isRoad(level, x, z)) return false;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return false;
        int g = ground(x, z);
        if (g < -1000 || g >= 100000 || Math.abs(g - lvl) > 3) return false;
        return treeCost(x, z, g) == 0;
    }

    private void ticket(int cx, int cz) {
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(cx, cz), 0);
    }

    private long minBlocked() {
        long m = Long.MAX_VALUE;
        for (long[] b : blocked) m = Math.min(m, b[0]);
        return m;
    }

    /** Edges that were waiting for a chunk go on as soon as it is loaded. */
    private void retryBlocked() {
        if (blocked.isEmpty()) return;
        List<long[]> ready = new ArrayList<>();
        for (Iterator<long[]> it = blocked.iterator(); it.hasNext(); ) {
            long[] b = it.next();
            int nx = ChunkPos.getX(b[1]) + DX[(int) b[2]], nz = ChunkPos.getZ(b[1]) + DZ[(int) b[2]];
            if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) != null) {
                ready.add(b);
                it.remove();
            }
        }
        if (!ready.isEmpty()) waited = 0;
        for (long[] b : ready) expand(b[1], ChunkPos.getX(b[1]), ChunkPos.getZ(b[1]), arrive.get(b[1]), (int) b[3], (int) b[2]);
    }

    private int prevLevel(long k) {
        if (!parent.containsKey(k)) return inLevel;
        long p = parent.get(k);
        return level(ground(ChunkPos.getX(p), ChunkPos.getZ(p)));
    }

    private void expand(long k, int x, int z, int d0, int c, int d) {
        int nx = x + DX[d], nz = z + DZ[d];
        long nk = key(nx, nz);
        if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > RADIUS || nk == doorKey || used.contains(nk)) return;
        if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
            long chunk = ChunkPos.pack(nx >> 4, nz >> 4);
            if (requested.add(chunk)) ticket(nx >> 4, nz >> 4);
            blocked.add(new long[]{c + 10, k, d, c});
            return;
        }
        int ny = ground(nx, nz);
        int from = ground(x, z);
        int walkFrom = level(from);
        boolean fromWet = from >= 100000;
        if (ny >= -1000 && ny < 100000 && !fromWet && ny - walkFrom >= 1) tryBore(k, x, z, d0, c, d, walkFrom);
        if (ny < -1000) return;
        boolean wet = ny >= 100000;
        int walkTo = level(ny);
        int dy = walkTo - walkFrom;
        if (Math.abs(dy) > 1) return;
        int bend = 0;
        if (d0 != START && d0 != d) {
            // a corner belongs on level ground: a stair at a bend would end in a wall
            if (prevLevel(k) != walkFrom || walkTo != walkFrom) bend = BEND_ON_SLOPE;
        }
        int add = 10 + bend + (int) (lattice(31, nx * 7 + d, nz) * JITTER) + (dy != 0 ? climbPenalty(k) : 0) + treeCost(nx, nz, walkTo)
            + wobble(nx, nz) + (d0 != START && d0 != d ? TURN : 0) + 8 * Math.abs(dy) + (wet ? 30 : 0);
        if (dy < 0 && x == sx && z == sz) add += END_STEP;
        else if (dy > 0 && RoadColumns.isOpenRoad(level, nx, nz)) add += END_STEP;
        int total = c + add;
        if (total >= cost.get(nk)) return;
        cost.put(nk, total);
        parent.put(nk, k);
        arrive.put(nk, d);
        bore.remove(nk);
        open.add(new long[]{total, nk});
    }

    /**
     * A straight tunnel at the walked level through the hill ahead: columns whose ground is at least ROOF above the
     * path get a roof; the bore ends where the ground comes back down to the level. Needs MIN_ROOFED roofed columns,
     * and the rock must be safe to cut.
     */
    private void tryBore(long k, int x, int z, int d0, int c, int d, int y) {
        int exit = -1, roofed = 0;
        for (int m = 1; m <= Tunnels.MAX_LEN; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            if (level.getChunkSource().getChunkNow(cx >> 4, cz >> 4) == null) return;
            int g = ground(cx, cz);
            if (g < -1000 || g >= 100000 || g < y - 1) return;
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
        long ek = key(x + DX[d] * exit, z + DZ[d] * exit);
        if (used.contains(ek)) return;
        for (int m = 1; m < exit; m++) if (used.contains(key(x + DX[d] * m, z + DZ[d] * m))) return;
        if (!Tunnels.safe(level, x, z, d, y, exit)) return;
        // a bore must not run under (or through) the stretch that led here
        Set<Long> cols = new HashSet<>();
        for (int m = 1; m <= exit; m++) cols.add(key(x + DX[d] * m, z + DZ[d] * m));
        for (long a = k; ; ) {
            if (cols.contains(a)) return;
            if (!parent.containsKey(a)) break;
            a = parent.get(a);
        }
        int bend = d0 != START && d0 != d ? (prevLevel(k) != y ? BEND_ON_SLOPE : TURN) : 0;
        int total = c + bend + borePer * exit + boreFixed;
        if (total >= cost.get(ek)) return;
        cost.put(ek, total);
        parent.put(ek, k);
        arrive.put(ek, d);
        bore.put(ek, exit);
        open.add(new long[]{total, ek});
    }

    /** Climbing several of the last few cells in a row is dear: on a slope the route zigzags across it with flat stretches between the rises. */
    private int climbPenalty(long k) {
        int cnt = 0;
        long a = k;
        for (int q = 0; q < 4; q++) {
            if (!parent.containsKey(a)) break;
            long pa = parent.get(a);
            if (level(ground(ChunkPos.getX(a), ChunkPos.getZ(a))) != level(ground(ChunkPos.getX(pa), ChunkPos.getZ(pa)))) cnt++;
            a = pa;
        }
        return 6 * cnt;
    }

    /** Leaves where the path would have to cut them, or a trunk beside it: dear, so the route goes round trees rather than through their canopy. */
    int treeCost(int x, int z, int walkY) {
        long k = key(x, z);
        int v = trees.get(k);
        if (v == UNKNOWN) {
            v = 0;
            for (int dy = 1; dy <= HousePaths.LEAF_ROOM; dy++)
                if (level.getBlockState(new BlockPos(x, walkY + dy, z)).is(BlockTags.LEAVES)) v += 14;
            for (Direction dd : DIRS)
                if (level.getBlockState(new BlockPos(x + dd.getStepX(), walkY + 1, z + dd.getStepZ())).is(BlockTags.LOGS)) v += 8;
            trees.put(k, v);
        }
        return v;
    }

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

    /** Surface block Y of a column the path may use (+100000 for water: a deck goes on top), CLOSED when it may not be used. */
    int ground(int x, int z) {
        long k = key(x, z);
        int v = ground.get(k);
        if (v == UNKNOWN) {
            v = passable(x, z);
            if (v != CLOSED || level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) ground.put(k, v);
        }
        return v;
    }

    private int passable(int x, int z) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return CLOSED;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState s = level.getBlockState(new BlockPos(x, top, z));
        if (s.is(Stake.BLOCK)) {
            // a waypoint (or another door) stands here: the ground is the block under it
            top--;
            s = level.getBlockState(new BlockPos(x, top, z));
        }
        if (s.is(Blocks.WATER)) {
            int below = top;
            while (below > level.getMinY() && level.getBlockState(new BlockPos(x, below, z)).is(Blocks.WATER) && top - below < 6) below--;
            return top - below >= 6 ? CLOSED : top + 100000;
        }
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LOGS) || (Retrofit.isCrafted(s) && !RoadColumns.isRoad(level, x, z))) return CLOSED;
        if (x == sx && z == sz) return stake.getY() - 1;
        return top;
    }
}
