package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.world.TerrainSampler;
import io.github.profetgit.countryroads.world.VillageOracle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The road network of one level. Edges are a relative-neighbourhood graph of villages (contains the minimum spanning
 * tree plus a few natural loops), decided from villages near each 512-block region only, so every chunk sees the
 * same answer no matter which order chunks generate in. Plans are computed on a planner pool and memoised.
 */
public final class RoadNetwork {
    public static final int MAX_EDGE = Integer.getInteger("countryroads.maxEdge", 2000);
    private static final int REGION_SHIFT = 9;
    private static final Map<ServerLevel, RoadNetwork> NETWORKS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4), r -> {
        Thread t = new Thread(r, "CountryRoads-planner-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public final ServerLevel level;
    public final VillageOracle oracle;
    public final TerrainSampler terrain;
    private final Planner planner;
    public final Camps camps;
    private final ConcurrentHashMap<Edge, CompletableFuture<EdgePlan>> plans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<Edge>> regionEdges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChunkMask> masks = new ConcurrentHashMap<>();
    public final AtomicLong waitNanos = new AtomicLong(), routeNanos = new AtomicLong();
    public final AtomicInteger routed = new AtomicInteger(), failed = new AtomicInteger();

    private RoadNetwork(ServerLevel level) {
        this.level = level;
        this.oracle = new VillageOracle(level, Set.of());
        this.terrain = new TerrainSampler(level);
        this.camps = new Camps(level, terrain);
        this.planner = new Planner(terrain, oracle, camps);
    }

    /** null outside the overworld or when the world has no villages. */
    public static RoadNetwork get(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return null;
        RoadNetwork n = NETWORKS.computeIfAbsent(level, RoadNetwork::new);
        return n.oracle.setCount() == 0 ? null : n;
    }

    public static java.util.concurrent.Executor executor() {
        return POOL;
    }

    public static void clear() {
        NETWORKS.clear();
    }

    public List<Edge> edgesInRegion(int rx, int rz) {
        return regionEdges.computeIfAbsent(ChunkPos.pack(rx, rz), k -> computeRegion(rx, rz));
    }

    private List<Edge> computeRegion(int rx, int rz) {
        int x0 = rx << REGION_SHIFT, z0 = rz << REGION_SHIFT, x1 = x0 + (1 << REGION_SHIFT) - 1, z1 = z0 + (1 << REGION_SHIFT) - 1;
        int reach = (int) (MAX_EDGE * Planner.ELLIPSE) + 64;
        int far = reach + MAX_EDGE;
        List<VillageOracle.Village> all = oracle.find((x0 - far) >> 4, (z0 - far) >> 4, (x1 + far) >> 4, (z1 + far) >> 4);
        List<VillageOracle.Village> near = all.stream().filter(v -> v.pos().getX() >= x0 - reach && v.pos().getX() <= x1 + reach
            && v.pos().getZ() >= z0 - reach && v.pos().getZ() <= z1 + reach).toList();
        List<Edge> out = new ArrayList<>();
        for (int i = 0; i < near.size(); i++) {
            for (int j = i + 1; j < near.size(); j++) {
                Edge e = Edge.of(near.get(i), near.get(j));
                long d2 = d2(e.a(), e.b());
                if (d2 > (long) MAX_EDGE * MAX_EDGE) continue;
                double d = Math.sqrt(d2);
                double minor = d / 2 * Math.sqrt(Planner.ELLIPSE * Planner.ELLIPSE - 1) + 32;
                if (boxSegmentDistance(x0, z0, x1, z1, e) > minor) continue;
                if (!relativeNeighbours(e, d2, all)) continue;
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    private static boolean relativeNeighbours(Edge e, long d2, List<VillageOracle.Village> all) {
        for (VillageOracle.Village c : all) {
            if (c.equals(e.a()) || c.equals(e.b())) continue;
            if (Math.max(d2(e.a(), c), d2(e.b(), c)) < d2) return false;
        }
        return true;
    }

    private static long d2(VillageOracle.Village a, VillageOracle.Village b) {
        long dx = a.pos().getX() - b.pos().getX(), dz = a.pos().getZ() - b.pos().getZ();
        return dx * dx + dz * dz;
    }

    private static double boxSegmentDistance(int x0, int z0, int x1, int z1, Edge e) {
        double ax = e.a().pos().getX(), az = e.a().pos().getZ(), bx = e.b().pos().getX(), bz = e.b().pos().getZ();
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= 32; k++) {
            double t = k / 32.0, px = ax + (bx - ax) * t, pz = az + (bz - az) * t;
            double dx = Math.max(Math.max(x0 - px, 0), px - x1), dz = Math.max(Math.max(z0 - pz, 0), pz - z1);
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return best - Math.hypot(e.b().pos().getX() - ax, e.b().pos().getZ() - az) / 64;
    }

    /**
     * Plans the road. Roads leaving a village in a similar direction share a trunk: the shorter ones are planned first
     * and the longer road follows them. The order comes from the village layout alone, so it is the same in every run.
     */
    public CompletableFuture<EdgePlan> plan(Edge e) {
        CompletableFuture<EdgePlan> f = plans.get(e);
        if (f != null) return f;
        List<CompletableFuture<EdgePlan>> deps = new ArrayList<>();
        for (Edge d : trunkDeps(e)) deps.add(plan(d));
        CompletableFuture<EdgePlan> mine = new CompletableFuture<>();
        CompletableFuture<EdgePlan> prev = plans.putIfAbsent(e, mine);
        if (prev != null) return prev;
        CompletableFuture.allOf(deps.toArray(CompletableFuture[]::new)).whenCompleteAsync((v, err) -> {
            List<EdgePlan> trunks = new ArrayList<>();
            for (var d : deps) {
                EdgePlan p = d.getNow(null);
                if (p != null) trunks.add(p);
            }
            mine.complete(planWithRetries(e, trunks));
        }, POOL);
        return mine;
    }

    private EdgePlan planWithRetries(Edge k, List<EdgePlan> trunks) {
        // planning is deterministic, so a transient failure (a race inside vanilla) is simply tried again
        for (int attempt = 1; ; attempt++) {
            try {
                EdgePlan p = planner.plan(k, trunks);
                if (p == null) failed.incrementAndGet();
                else {
                    routed.incrementAndGet();
                    routeNanos.addAndGet(p.routeNanos);
                }
                return p;
            } catch (Throwable t) {
                if (attempt >= 3) {
                    CountryRoads.LOG.error("Planning road {} failed", k, t);
                    failed.incrementAndGet();
                    return null;
                }
                CountryRoads.LOG.warn("Planning road {} failed ({}), retrying", k, t.toString());
            }
        }
    }


    /**
     * The roads this one may share the way with: shorter roads that start at one of its villages, or whose straight
     * line passes within 48 blocks of its own. They are planned first; this road snaps onto them where it runs alongside.
     */
    public List<Edge> trunkDeps(Edge e) {
        List<Edge> out = new ArrayList<>();
        java.util.Set<Edge> seen = new java.util.HashSet<>();
        for (VillageOracle.Village v : new VillageOracle.Village[]{e.a(), e.b()}) {
            for (Edge d : edgesInRegion(v.pos().getX() >> REGION_SHIFT, v.pos().getZ() >> REGION_SHIFT)) {
                if (d.equals(e) || !seen.add(d) || !shorter(d, e)) continue;
                boolean shares = d.a().equals(v) || d.b().equals(v) || d.a().equals(e.a()) || d.a().equals(e.b()) || d.b().equals(e.a()) || d.b().equals(e.b());
                if (shares || segmentDistance(d, e) < 48) out.add(d);
            }
        }
        out.sort((p, q) -> shorter(p, q) ? -1 : shorter(q, p) ? 1 : 0);
        return out;
    }

    private static double segmentDistance(Edge p, Edge q) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= 24; k++) {
            double t = k / 24.0;
            double px = p.a().pos().getX() + (p.b().pos().getX() - p.a().pos().getX()) * t, pz = p.a().pos().getZ() + (p.b().pos().getZ() - p.a().pos().getZ()) * t;
            double ax = q.a().pos().getX(), az = q.a().pos().getZ(), bx = q.b().pos().getX(), bz = q.b().pos().getZ();
            double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
            double u = len2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (pz - az) * dz) / len2, 0, 1);
            best = Math.min(best, Math.hypot(ax + u * dx - px, az + u * dz - pz));
        }
        return best;
    }

    private static boolean shorter(Edge p, Edge q) {
        long dp = d2(p.a(), p.b()), dq = d2(q.a(), q.b());
        if (dp != dq) return dp < dq;
        int c = Long.compare(p.a().key(), q.a().key());
        return c != 0 ? c < 0 : p.b().key() < q.b().key();
    }

    /** Plans whose road can come near this chunk (blocks until they are ready). */
    public List<EdgePlan> plansNear(int cx, int cz, int margin) {
        int bx = cx << 4, bz = cz << 4;
        List<CompletableFuture<EdgePlan>> pending = new ArrayList<>();
        for (Edge e : edgesInRegion(bx >> REGION_SHIFT, bz >> REGION_SHIFT)) {
            double d = e.length();
            double minor = d / 2 * Math.sqrt(Planner.ELLIPSE * Planner.ELLIPSE - 1) + margin;
            if (boxSegmentDistance(bx, bz, bx + 15, bz + 15, e) <= minor) pending.add(plan(e));
        }
        List<EdgePlan> out = new ArrayList<>();
        long t0 = System.nanoTime();
        for (var f : pending) {
            EdgePlan p = f.join();
            if (p != null) out.add(p);
        }
        waitNanos.addAndGet(System.nanoTime() - t0);
        return out;
    }

    /** Edges whose road can come within margin of the block box (the filter plansNear uses), without planning them. */
    public List<Edge> edgesNear(int x0, int z0, int x1, int z1, int margin) {
        java.util.Set<Edge> out = new java.util.LinkedHashSet<>();
        for (int rx = x0 >> REGION_SHIFT; rx <= x1 >> REGION_SHIFT; rx++)
            for (int rz = z0 >> REGION_SHIFT; rz <= z1 >> REGION_SHIFT; rz++)
                for (Edge e : edgesInRegion(rx, rz)) {
                    double minor = e.length() / 2 * Math.sqrt(Planner.ELLIPSE * Planner.ELLIPSE - 1) + margin;
                    if (boxSegmentDistance(x0, z0, x1, z1, e) <= minor) out.add(e);
                }
        return new ArrayList<>(out);
    }

    /** The plan whose wayside camp starts in this chunk, if any. */
    public EdgePlan campAt(int cx, int cz) {
        if (!camps.available()) return null;
        long key = ChunkPos.pack(cx, cz);
        for (EdgePlan p : plansNear(cx, cz, 80)) if (p.campChunk == key) return p;
        return null;
    }

    /** Road data of a chunk; blocks until the plans touching it are ready. */
    public ChunkMask mask(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        ChunkMask m = masks.get(key);
        if (m != null) return m;
        List<EdgePlan> ready = new ArrayList<>();
        List<EdgePlan> near = plansNear(cx, cz, 60);
        List<Field> fields = fieldsOver(cx, cz, near);
        for (EdgePlan p : near) if (p.touches(key) || hasDecorOrCamp(p, cx, cz) || hasFieldOver(p, cx, cz)) ready.add(p);
        if (ready.isEmpty()) m = ChunkMask.EMPTY;
        else {
            List<VillageOracle.Footprint> villages = new ArrayList<>();
            for (var v : oracle.find(cx - 12, cz - 12, cx + 12, cz + 12)) villages.add(oracle.footprint(v));
            m = ChunkMask.build(cx, cz, ready, villages, near, fields);
            if (!m.any()) m = ChunkMask.EMPTY;
        }
        if (masks.size() > 50_000) masks.clear();
        masks.put(key, m);
        return m;
    }

    /** Street bands of the villages whose streets can reach this chunk (empty when there are none or the feature is off). */
    public List<io.github.profetgit.countryroads.world.StreetBands> streets(int cx, int cz) {
        if (!io.github.profetgit.countryroads.paint.StreetPainter.enabled()) return List.of();
        List<io.github.profetgit.countryroads.world.StreetBands> out = new ArrayList<>();
        int x0 = cx << 4, z0 = cz << 4;
        for (var v : oracle.find(cx - 12, cz - 12, cx + 12, cz + 12)) {
            VillageOracle.Footprint f = oracle.footprint(v);
            if (f.bands().isEmpty()) continue;
            for (var b : f.pieces())
                if (b.maxX() >= x0 && b.minX() <= x0 + 15 && b.maxZ() >= z0 && b.minZ() <= z0 + 15) {
                    out.add(f.bands());
                    break;
                }
        }
        return out;
    }

    private static boolean hasFieldOver(EdgePlan p, int cx, int cz) {
        for (Field f : p.fields) if (f.nearChunk(cx, cz)) return true;
        return false;
    }

    /**
     * The wheat fields (after the spacing check against every field within reach) whose blob can touch the chunk. The
     * wider plan list is only fetched when a candidate field is there, so most chunks never pay for it.
     */
    private List<Field> fieldsOver(int cx, int cz, List<EdgePlan> near) {
        List<Field> cand = new ArrayList<>();
        for (EdgePlan p : near) for (Field f : p.fields) if (f.nearChunk(cx, cz)) cand.add(f);
        if (cand.isEmpty()) return List.of();
        List<Field> all = new ArrayList<>();
        for (EdgePlan p : plansNear(cx, cz, 180)) all.addAll(p.fields);
        return ChunkMask.survivors(cand, all);
    }

    private static boolean hasDecorOrCamp(EdgePlan p, int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        for (EdgePlan.Decor d : p.decor) if (d.chunk() == key) return true;
        return false;
    }

    public List<EdgePlan> readyPlans() {
        List<EdgePlan> out = new ArrayList<>();
        for (var f : plans.values()) if (f.isDone() && f.join() != null) out.add(f.join());
        return out;
    }
}
