package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.Edge;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.Planner;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.TerrainSampler;
import io.github.profetgit.countryroads.world.VillageOracle.Village;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * M3 network check over a square: plans every road whose villages lie near it, then compares the road graph with the
 * land. Land = the coarse router's 16-block cells it may enter (not ocean water, not water deeper than DEEP, not near the
 * build limit), flood-filled 8-connected over the square plus a margin. A land piece holding villages of more than one
 * road component is a missed connection. Also: isolated villages, failed roads, road samples on ocean water, planning
 * time per 512-block region. Runs off the server thread; the harness waits through the task queue.
 */
final class NetworkCheck {
    private static final int GRID = io.github.profetgit.countryroads.route.Router.CELL, PAD = 512;

    private NetworkCheck() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    static int network(CommandSourceStack src, int radius) {
        RoadNetwork n = RoadNetwork.get(src.getLevel());
        if (n == null) {
            report(src, "CR-NET none");
            return 0;
        }
        BlockPos at = BlockPos.containing(src.getPosition());
        int x0 = at.getX() - radius, z0 = at.getZ() - radius, x1 = at.getX() + radius, z1 = at.getZ() + radius;
        CompletableFuture<List<String>> job = CompletableFuture.supplyAsync(() -> run(n, x0, z0, x1, z1), r -> {
            Thread t = new Thread(r, "CountryRoads-netcheck");
            t.setDaemon(true);
            t.start();
        });
        DevCommands.queue(() -> {
            if (!job.isDone()) return false;
            try {
                for (String line : job.join()) report(src, line);
            } catch (Throwable t) {
                report(src, "CR-NET FAIL " + t);
            }
            return true;
        });
        return 1;
    }

    private static List<String> run(RoadNetwork n, int x0, int z0, int x1, int z1) {
        List<String> out = new ArrayList<>();
        long t0 = System.nanoTime();
        List<Village> inside = n.oracle.find(x0 >> 4, z0 >> 4, x1 >> 4, z1 >> 4);
        Set<Edge> edges = new LinkedHashSet<>();
        for (int rx = x0 >> 9; rx <= x1 >> 9; rx++)
            for (int rz = z0 >> 9; rz <= z1 >> 9; rz++) edges.addAll(n.edgesInRegion(rx, rz));
        Map<Edge, CompletableFuture<EdgePlan>> futures = new HashMap<>();
        for (Edge e : edges) futures.put(e, n.plan(e));
        Map<Edge, EdgePlan> plans = new HashMap<>();
        for (var en : futures.entrySet()) plans.put(en.getKey(), en.getValue().join());
        long planWall = System.nanoTime() - t0;

        // road components (union-find over every endpoint)
        Map<Village, Village> parent = new HashMap<>();
        for (Village v : inside) parent.put(v, v);
        for (Edge e : edges) {
            parent.putIfAbsent(e.a(), e.a());
            parent.putIfAbsent(e.b(), e.b());
        }
        Map<Village, Integer> degree = new HashMap<>(), tried = new HashMap<>();
        int routed = 0, failed = 0, longEdges = 0, longRouted = 0;
        for (Edge e : edges) {
            if (e.length() > 1200) {
                longEdges++;
                if (plans.get(e) != null) longRouted++;
                out.add("CR-NET-LONG " + xz(e.a()) + " -> " + xz(e.b()) + " straight=" + (int) e.length() + (plans.get(e) == null ? " NONE" : " length=" + plans.get(e).length()));
            }
            tried.merge(e.a(), 1, Integer::sum);
            tried.merge(e.b(), 1, Integer::sum);
            if (plans.get(e) == null) {
                failed++;
                continue;
            }
            routed++;
            degree.merge(e.a(), 1, Integer::sum);
            degree.merge(e.b(), 1, Integer::sum);
            union(parent, e.a(), e.b());
        }

        // land pieces
        TerrainSampler ts = n.terrain;
        int gx0 = Math.floorDiv(x0 - PAD, GRID), gz0 = Math.floorDiv(z0 - PAD, GRID);
        int w = Math.floorDiv(x1 + PAD, GRID) - gx0 + 1, h = Math.floorDiv(z1 + PAD, GRID) - gz0 + 1;
        int top = ts.maxY() - 12;
        boolean[] land = new boolean[w * h];
        long ts0 = System.nanoTime();
        IntStream.range(0, w * h).parallel().forEach(k -> {
            TerrainSampler.Sample s = ts.sample((gx0 + k % w) * GRID + GRID / 2, (gz0 + k / w) * GRID + GRID / 2);
            int y = s.wet() ? s.water() + 1 : s.ground();
            land[k] = !(s.ocean() && s.wet()) && !(s.wet() && s.depth() > io.github.profetgit.countryroads.route.Router.DEEP) && y <= top;
        });
        long sampleNanos = System.nanoTime() - ts0;
        int[] label = new int[w * h];
        int labels = 0;
        int[] stack = new int[w * h];
        for (int k = 0; k < w * h; k++) {
            if (!land[k] || label[k] != 0) continue;
            labels++;
            int sp = 0;
            stack[sp++] = k;
            label[k] = labels;
            while (sp > 0) {
                int c = stack[--sp], ci = c % w, cj = c / w;
                for (int di = -1; di <= 1; di++)
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h) continue;
                        int q = nj * w + ni;
                        if (land[q] && label[q] == 0) {
                            label[q] = labels;
                            stack[sp++] = q;
                        }
                    }
            }
        }
        Map<Village, Integer> landOf = new HashMap<>();
        for (Village v : inside) {
            int ci = Math.floorDiv(v.pos().getX(), GRID) - gx0, cj = Math.floorDiv(v.pos().getZ(), GRID) - gz0, best = 0;
            for (int r = 0; r <= 2 && best == 0; r++)
                for (int di = -r; di <= r && best == 0; di++)
                    for (int dj = -r; dj <= r && best == 0; dj++) {
                        int ni = ci + di, nj = cj + dj;
                        if (ni >= 0 && nj >= 0 && ni < w && nj < h) best = label[nj * w + ni];
                    }
            landOf.put(v, best);
        }

        // splits: a land piece with villages of several road components
        Map<Integer, Map<Village, List<Village>>> byLand = new HashMap<>();
        for (Village v : inside) byLand.computeIfAbsent(landOf.get(v), k -> new HashMap<>()).computeIfAbsent(find(parent, v), k -> new ArrayList<>()).add(v);
        Set<Village> roots = new java.util.HashSet<>();
        for (Village v : inside) roots.add(find(parent, v));
        int missed = 0, water = 0;
        for (var en : byLand.entrySet()) {
            if (en.getKey() == 0 || en.getValue().size() < 2) continue;
            // the closest pair between this piece's road components, per component
            List<List<Village>> comps = new ArrayList<>(en.getValue().values());
            comps.sort((p, q) -> Integer.compare(p.size(), q.size()));
            for (List<Village> c : comps.subList(0, comps.size() - 1)) {
                double best = Double.MAX_VALUE;
                Village bv = null, bo = null;
                for (Village v : c)
                    for (List<Village> o : comps) {
                        if (o == c) continue;
                        for (Village u : o) {
                            double d = Math.hypot(v.pos().getX() - u.pos().getX(), v.pos().getZ() - u.pos().getZ());
                            if (d < best) {
                                best = d;
                                bv = v;
                                bo = u;
                            }
                        }
                    }
                // a miss only when some pair across the split is within MAX_EDGE and joined by land inside the router's
                // ellipse; otherwise water (a lake or a wide river the road would have to go far around) separates them
                boolean reachable = false;
                for (Village v : c)
                    for (List<Village> o : comps) {
                        if (o == c) continue;
                        for (Village u : o)
                            if (!reachable && Math.hypot(v.pos().getX() - u.pos().getX(), v.pos().getZ() - u.pos().getZ()) <= RoadNetwork.MAX_EDGE)
                                reachable = joined(v, u, Planner.ELLIPSE, land, w, h, gx0, gz0);
                    }
                if (reachable) missed++;
                else water++;
                out.add("CR-NET-" + (reachable ? "SPLIT" : "WATER") + " land=" + en.getKey() + " villages=" + c.size() + " at=" + xz(bv) + " nearestOther=" + xz(bo) + " d=" + (int) best
                    + " triedEdges=" + c.stream().mapToInt(v -> tried.getOrDefault(v, 0)).sum());
            }
        }
        int isolated = 0;
        for (Village v : inside) {
            if (degree.getOrDefault(v, 0) > 0) continue;
            isolated++;
            double best = Double.MAX_VALUE;
            for (Village u : inside) if (u != v) best = Math.min(best, Math.hypot(v.pos().getX() - u.pos().getX(), v.pos().getZ() - u.pos().getZ()));
            out.add("CR-NET-ISOLATED " + xz(v) + " land=" + landOf.get(v) + " edgesTried=" + tried.getOrDefault(v, 0) + " nearestVillage=" + (int) best);
        }
        for (Edge e : edges)
            if (plans.get(e) == null) out.add("CR-NET-FAILED " + xz(e.a()) + " -> " + xz(e.b()) + " straight=" + (int) e.length()
                + " landEllipse=" + landEllipse(e, land, w, h, gx0, gz0));

        // ocean water under a road, planning time per region (by edge midpoint)
        int ocean = 0;
        Map<Long, Long> regionNanos = new HashMap<>();
        long routeNanos = 0;
        for (var en : plans.entrySet()) {
            EdgePlan p = en.getValue();
            if (p == null) continue;
            routeNanos += p.routeNanos;
            Edge e = en.getKey();
            long reg = ((long) ((e.a().pos().getX() + e.b().pos().getX()) / 2 >> 9) << 32) ^ (((e.a().pos().getZ() + e.b().pos().getZ()) / 2 >> 9) & 0xffffffffL);
            regionNanos.merge(reg, p.routeNanos, Long::sum);
            for (int i = 0; i < p.length(); i += 4) {
                TerrainSampler.Sample s = ts.sample((int) Math.floor(p.x[i]), (int) Math.floor(p.z[i]));
                if (s.ocean() && s.wet() && s.depth() > 2) {
                    ocean++;
                    if (ocean <= 5) out.add("CR-NET-OCEAN " + (int) p.x[i] + "," + (int) p.z[i] + " depth=" + s.depth() + " kind=" + p.kind[i] + " road " + xz(e.a()) + " -> " + xz(e.b()));
                }
            }
        }
        long maxRegion = regionNanos.values().stream().mapToLong(Long::longValue).max().orElse(0);
        int nComp = 0;
        for (Village r : roots) nComp++;
        Set<Integer> landPieces = new java.util.HashSet<>(landOf.values());
        boolean ok = missed == 0 && ocean == 0;
        out.add(0, String.format("CR-NET %s villages=%d edges=%d routed=%d failed=%d roadComponents=%d landPieces=%d missed=%d isolated=%d oceanSamples=%d"
                + " long=%d/%d waterSplits=%d routeMs=%d regionMsMax=%d regions=%d planWallS=%.1f landCells=%d landSampleS=%.1f",
            ok ? "PASS" : "FAIL", inside.size(), edges.size(), routed, failed, nComp, landPieces.size(), missed, isolated, ocean, longRouted, longEdges, water,
            routeNanos / 1_000_000, maxRegion / 1_000_000, regionNanos.size(), planWall / 1e9, w * h, sampleNanos / 1e9));
        return out;
    }

    /** Smallest ellipse factor whose land cells (8-connected, no turn rule) join the two villages; "none" past 8. */
    private static String landEllipse(Edge e, boolean[] land, int w, int h, int gx0, int gz0) {
        for (double f : new double[]{1.6, 2.0, 2.4, 3.0, 4.0, 6.0, 8.0})
            if (joined(e.a(), e.b(), f, land, w, h, gx0, gz0)) return String.valueOf(f);
        return "none";
    }

    /** The two villages are joined by land cells (8-connected, no turn rule) inside the ellipse of factor f. */
    private static boolean joined(Village a, Village b, double f, boolean[] land, int w, int h, int gx0, int gz0) {
        int ai = Math.floorDiv(a.pos().getX(), GRID) - gx0, aj = Math.floorDiv(a.pos().getZ(), GRID) - gz0;
        int bi = Math.floorDiv(b.pos().getX(), GRID) - gx0, bj = Math.floorDiv(b.pos().getZ(), GRID) - gz0;
        double d = Math.hypot(bi - ai, bj - aj);
        {
            double limit = d * f + 4;
            boolean[] seen = new boolean[w * h];
            int[] queue = new int[w * h];
            int qh = 0, qt = 0;
            for (int di = -2; di <= 2; di++)
                for (int dj = -2; dj <= 2; dj++) {
                    int i = ai + di, j = aj + dj;
                    if (i >= 0 && j >= 0 && i < w && j < h && land[j * w + i] && !seen[j * w + i]) {
                        seen[j * w + i] = true;
                        queue[qt++] = j * w + i;
                    }
                }
            while (qh < qt) {
                int c = queue[qh++], ci = c % w, cj = c / w;
                if (Math.abs(ci - bi) <= 2 && Math.abs(cj - bj) <= 2) return true;
                for (int di = -1; di <= 1; di++)
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h) continue;
                        int q = nj * w + ni;
                        if (!land[q] || seen[q] || Math.hypot(ni - ai, nj - aj) + Math.hypot(ni - bi, nj - bj) > limit) continue;
                        seen[q] = true;
                        queue[qt++] = q;
                    }
            }
        }
        return false;
    }

    private static String xz(Village v) {
        return v == null ? "?" : v.pos().getX() + "," + v.pos().getZ();
    }

    private static Village find(Map<Village, Village> parent, Village v) {
        Village p = parent.get(v);
        while (p != v) {
            Village g = parent.get(p);
            parent.put(v, g);
            v = p;
            p = g;
        }
        return v;
    }

    private static void union(Map<Village, Village> parent, Village a, Village b) {
        Village ra = find(parent, a), rb = find(parent, b);
        if (ra == rb) return;
        if (Long.compare(ra.key(), rb.key()) < 0) parent.put(rb, ra);
        else parent.put(ra, rb);
    }
}
