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
    public static final int MAX_EDGE = 1200;
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

    public CompletableFuture<EdgePlan> plan(Edge e) {
        return plans.computeIfAbsent(e, k -> CompletableFuture.supplyAsync(() -> {
            // planning is deterministic, so a transient failure (a race inside vanilla) is simply tried again
            for (int attempt = 1; ; attempt++) {
                try {
                    EdgePlan p = planner.plan(k);
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
        }, POOL));
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
        for (EdgePlan p : plansNear(cx, cz, 40)) if (p.touches(key) || hasDecorOrCamp(p, cx, cz)) ready.add(p);
        if (ready.isEmpty()) m = ChunkMask.EMPTY;
        else {
            List<VillageOracle.Footprint> villages = new ArrayList<>();
            for (var v : oracle.find(cx - 12, cz - 12, cx + 12, cz + 12)) villages.add(oracle.footprint(v));
            m = ChunkMask.build(cx, cz, ready, villages);
            if (!m.any()) m = ChunkMask.EMPTY;
        }
        if (masks.size() > 50_000) masks.clear();
        masks.put(key, m);
        return m;
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
