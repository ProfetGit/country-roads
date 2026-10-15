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
    private static EdgePlan noPlan(Edge e, String why) {
        io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-PLAN-NONE " + e.a().pos().toShortString() + " -> " + e.b().pos().toShortString() + ": " + why);
        return null;
    }

    public EdgePlan plan(Edge e, List<EdgePlan> trunkPlans) {
        boolean[] picked = new boolean[1];
        EdgePlan p = plan(e, trunkPlans, true, picked);
        // a dry village exit the coarse router cannot reach (its cells saw the lake around it): the nearer exit as before
        return p == null && picked[0] ? plan(e, trunkPlans, false, picked) : p;
    }

    private EdgePlan plan(Edge e, List<EdgePlan> trunkPlans, boolean dryDocks, boolean[] picked) {
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
        Docking.Connector ca = null, cb = null;
        if (trunkA == null) {
            ca = Docking.connect(fa, e.b().pos().getX(), e.b().pos().getZ(), dryDocks ? terrain : null);
            if (ca == null && !fa.bands().isEmpty())
                io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOCK-MISS village " + e.a().pos().toShortString() + " bands=" + fa.bands().size() + " pieces=" + fa.pieces().size() + " toward " + e.b().pos().toShortString());
            if (ca != null) ja = new int[]{ca.exitX(), ca.exitZ()};
            picked[0] |= ca != null && ca.dryPick();
        }
        if (trunkB == null) {
            cb = Docking.connect(fb, e.a().pos().getX(), e.a().pos().getZ(), dryDocks ? terrain : null);
            if (cb == null && !fb.bands().isEmpty())
                io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOCK-MISS village " + e.b().pos().toShortString() + " bands=" + fb.bands().size() + " pieces=" + fb.pieces().size() + " toward " + e.a().pos().toShortString());
            if (cb != null) jb = new int[]{cb.exitX(), cb.exitZ()};
            picked[0] |= cb != null && cb.dryPick();
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet trunkCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (Trunk t : trunks)
            for (int i = 0; i < t.p.length(); i += 4)
                trunkCells.add(Router.cellKey(Math.floorDiv((int) t.p.x[i], Router.CELL), Math.floorDiv((int) t.p.z[i], Router.CELL)));
        Router.Result r = router.route(ja[0], ja[1], jb[0], jb[1], ELLIPSE, trunkCells::contains);
        if (r == null) return noPlan(e, "no route " + ja[0] + "," + ja[1] + " -> " + jb[0] + "," + jb[1]);
        double straight = Math.hypot(jb[0] - ja[0], jb[1] - ja[1]);
        if (r.cost() > straight * 10 * 14) return noPlan(e, "route cost " + r.cost() + " over " + Math.round(straight * 140));

        List<double[]> pts = new ArrayList<>();
        for (int[] p : r.points()) pts.add(new double[]{p[0] + 0.5, p[1] + 0.5});
        pts.set(0, new double[]{ja[0] + 0.5, ja[1] + 0.5});
        pts.set(pts.size() - 1, new double[]{jb[0] + 0.5, jb[1] + 0.5});
        for (int k = 0; k < 4; k++) pts = chaikin(pts);
        List<double[]> line = resample(pts, 1.0);
        if (line.size() > straight * 2.3 + 64) return noPlan(e, "route length " + line.size() + " for straight " + Math.round(straight));
        int connA = 0, connB = 0;
        if (ca != null) {
            List<double[]> c = connectorLine(ca);
            double[] first = line.getFirst();
            while (c.size() > 1 && Math.hypot(c.getLast()[0] - first[0], c.getLast()[1] - first[1]) < 0.6) c.removeLast();
            line.addAll(0, c);
            connA = c.size();
        }
        if (cb != null) {
            List<double[]> c = connectorLine(cb);
            java.util.Collections.reverse(c);
            double[] last = line.getLast();
            while (c.size() > 1 && Math.hypot(c.getFirst()[0] - last[0], c.getFirst()[1] - last[1]) < 0.6) c.removeFirst();
            line.addAll(c);
            connB = c.size();
        }

        // snap onto existing roads wherever this one runs alongside them: their exact line and heights
        int L = line.size();
        float[] pinY = new float[L];
        boolean[] onTrunk = new boolean[L];
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
                // a road that leaves the shared line for a few samples (it bulged more than 9 blocks off it) is still on
                // it: two shared stretches with a short gap overlapped there at different heights
                for (int k = i + 1; k < L && gap <= SHARE_GAP; k++) {
                    if (match[k] >= 0) {
                        b = k;
                        gap = 0;
                    } else gap++;
                }
                i = b + 1;
                if (TRACE_BRIDGES) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRUNK edge " + e.a().pos().toShortString() + "->" + e.b().pos().toShortString()
                    + " trunk " + t.p.edge.a().pos().toShortString() + "->" + t.p.edge.b().pos().toShortString() + " matched " + a + ".." + b + " of " + L
                    + " at " + (int) line.get(a)[0] + "," + (int) line.get(a)[1]);
                if (b - a + 1 < 12) {
                    // a crossing, too short to share the line: the road meets the other one at its height there (two
                    // roads crossed with the later one's bridge approach three blocks above the earlier one)
                    int best = -1;
                    double bestD = 2.5 * 2.5;
                    for (int k = a; k <= b; k++) {
                        if (match[k] < 0 || !Float.isNaN(pinY[k])) continue;
                        double[] q = line.get(k);
                        double dx = t.p.x[match[k]] - q[0], dz = t.p.z[match[k]] - q[1], d2 = dx * dx + dz * dz;
                        if (d2 < bestD) {
                            bestD = d2;
                            best = k;
                        }
                    }
                    if (best > 0 && best < L - 1) {
                        pinY[best] = t.p.y[match[best]];
                        pinKind[best] = t.p.kind[match[best]];
                    }
                    // every sample whose road overlaps the other one there shares its height too (two roads meeting at
                    // a shallow angle overlapped for a few blocks 1.5 apart in height)
                    for (int k = Math.max(1, a); k <= Math.min(L - 2, b); k++) {
                        if (match[k] < 0 || !Float.isNaN(pinY[k]) || t.p.kind[match[k]] != EdgePlan.GROUND) continue;
                        double[] q = line.get(k);
                        double dx = t.p.x[match[k]] - q[0], dz = t.p.z[match[k]] - q[1];
                        if (dx * dx + dz * dz < OVERLAP * OVERLAP && !Boolean.getBoolean("countryroads.noOverlapPin")) {
                            pinY[k] = t.p.y[match[k]];
                            pinKind[k] = EdgePlan.GROUND;
                        }
                    }
                    continue;
                }
                double[] offA = null, offB = null;
                int j = match[a];
                for (int k = a; k <= b; k++) {
                    if (match[k] >= 0) j = match[k];
                    else {
                        // inside a gap: walk the trunk evenly from the last matched sample to the next one
                        int k1 = k;
                        while (k1 < b && match[k1] < 0) k1++;
                        int prev = k - 1;
                        while (prev > a && match[prev] < 0) prev--;
                        if (match[k1] >= 0 && match[prev] >= 0)
                            j = (int) Math.round(match[prev] + (match[k1] - match[prev]) * (double) (k - prev) / (k1 - prev));
                    }
                    double[] q = line.get(k);
                    if (k == a) offA = new double[]{t.p.x[j] - q[0], t.p.z[j] - q[1]};
                    if (k == b) offB = new double[]{t.p.x[j] - q[0], t.p.z[j] - q[1]};
                    line.set(k, new double[]{t.p.x[j], t.p.z[j]});
                    pinY[k] = t.p.y[j];
                    onTrunk[k] = true;
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

        if (!Boolean.getBoolean("countryroads.noNudge")) nudgeOffFlanks(line, pinY, connA, connB, trunks);
        int start = 0, end = line.size() - 1;
        if (ca == null) {
            while (start < end && fa.contains((int) Math.floor(line.get(start)[0]), (int) Math.floor(line.get(start)[1]), 1)) start++;
            start = Math.max(0, start - 1);
        }
        if (cb == null) {
            while (end > start && fb.contains((int) Math.floor(line.get(end)[0]), (int) Math.floor(line.get(end)[1]), 1)) end--;
            end = Math.min(line.size() - 1, end + 1);
        }
        int n = end - start + 1;
        if (n < 8) return noPlan(e, "only " + n + " samples outside the villages");

        float[] x = new float[n], z = new float[n], ground = new float[n], water = new float[n], hw = new float[n], pin = new float[n];
        boolean[] shared = new boolean[n];
        byte[] kindPin = new byte[n];
        boolean[] river = new boolean[n];
        byte[] wood = new byte[n];
        for (int i = 0; i < n; i++) {
            double[] p = line.get(start + i);
            x[i] = (float) p[0];
            z[i] = (float) p[1];
            pin[i] = pinY[start + i];
            shared[i] = onTrunk[start + i];
            kindPin[i] = pinKind[start + i];
        }
        for (int i = 0; i < n; i++) sampleAt(i, x, z, ground, water, river);
        // village ends meet the street at its own height
        if (Float.isNaN(pin[0]) && water[0] <= ground[0]) pin[0] = ground[0];
        if (Float.isNaN(pin[n - 1]) && water[n - 1] <= ground[n - 1]) pin[n - 1] = ground[n - 1];
        byte[] kind = new byte[n];
        // bridges cross straight: the wobbling line over the water made every rail a staircase. Again while a crossing
        // still runs on into the road's bend (water continuing past the straightened part): the straight part grows
        // each round straightens the original line, with the crossings found on the previous round's line (a crossing
        // that grows into the bend is straightened further), until the crossings stop changing
        float[] x0 = x.clone(), z0 = z.clone(), ground0 = ground.clone(), water0 = water.clone();
        boolean[] river0 = river.clone();
        profile(ground, water, river, kind, pin, kindPin, connA, connB);
        // crossings only grow from round to round (a crossing that flipped between two extents never settled)
        boolean[] crossing = new boolean[n];
        byte[] use = new byte[n];
        for (int round = 0; round < Integer.getInteger("countryroads.bridgeRounds", 12); round++) {
            boolean grew = round == 0;
            for (int i = 0; i < n; i++) {
                // only water grows a crossing: dry trestle under a raised deck on the moved line joined it too, and one
                // crossing of 12 wet samples grew round by round into a 442-sample viaduct
                if (kind[i] == EdgePlan.BRIDGE && water0[i] > ground0[i] && !crossing[i]) {
                    crossing[i] = true;
                    grew = true;
                }
                use[i] = crossing[i] ? EdgePlan.BRIDGE : kind[i];
            }
            if (!grew) break;
            System.arraycopy(x0, 0, x, 0, n);
            System.arraycopy(z0, 0, z, 0, n);
            System.arraycopy(ground0, 0, ground, 0, n);
            System.arraycopy(water0, 0, water, 0, n);
            System.arraycopy(river0, 0, river, 0, n);
            boolean[] moved = straighten(x, z, use, water, ground, pin, (px, pz) -> {
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                return fa.inOther(bx, bz, 2) || fb.inOther(bx, bz, 2) || nearTrunk(trunks, px, pz);
            }, (px, pz) -> terrain.sample((int) Math.floor(px), (int) Math.floor(pz)).wet());
            for (int i = 0; i < n; i++) if (moved[i]) sampleAt(i, x, z, ground, water, river);
            profile(ground, water, river, kind, pin, kindPin, connA, connB);
        }
        for (int i = 0; i < n; i += 16) {
            byte w = Palette.wood(terrain.biome((int) x[i], (int) water[i], (int) z[i]));
            for (int k = i; k < Math.min(n, i + 16); k++) wood[k] = w;
        }
        float[] y = profile(ground, water, river, kind, pin, kindPin, connA, connB);
        if (TRACE_BRIDGES) {
            StringBuilder w = new StringBuilder();
            for (int q = 0; q < Math.min(n, 80); q++) w.append(water[q] > ground[q] ? "GCBWT".charAt(kind[q]) : Character.toLowerCase("GCBWT".charAt(kind[q]))).append(Math.round(y[q])).append(Float.isNaN(pin[q]) ? "" : "p").append(' ');
            io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-PROFILE n=" + n + " " + w);
        }
        // whatever crossing is still bent (it grew into a bend the rounds could not follow) is laid on the chord between its
        // own two ends: the ends stay, so the road either side is unchanged, and the deck is level, so heights hold
        boolean[] chorded = Boolean.getBoolean("countryroads.noChord") ? new boolean[n] : chordBridges(x, z, kind, water, ground, pin, (px, pz) -> {
            int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
            return fa.inOther(bx, bz, 2) || fb.inOther(bx, bz, 2) || nearTrunk(trunks, px, pz);
        }, (px, pz) -> terrain.sample((int) Math.floor(px), (int) Math.floor(pz)).wet(), this::deepAt);
        // again while a crossing's ease bent a neighbour that was already straight
        for (int pass = 0; pass < 3 && !Boolean.getBoolean("countryroads.noChord"); pass++) {
            boolean[] more = chordBridges(x, z, kind, water, ground, pin, (px, pz) -> {
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                return fa.inOther(bx, bz, 2) || fb.inOther(bx, bz, 2) || nearTrunk(trunks, px, pz);
            }, (px, pz) -> terrain.sample((int) Math.floor(px), (int) Math.floor(pz)).wet(), this::deepAt);
            boolean any = false;
            for (int i = 0; i < n; i++) if (more[i]) any = chorded[i] = true;
            if (!any) break;
        }
        boolean anyChord = false;
        for (int i = 0; i < n; i++)
            if (chorded[i]) {
                sampleAt(i, x, z, ground, water, river);
                anyChord = true;
            }
        // profiled once more: the axis line runs on over the water past the crossing and its ease swings the road beside
        // it, and ground samples moved out onto a lake kept their kind: the painter filled them up from the bed (a cobble
        // wall under the water beside a stone bridge). Once only: chording again what this profile finds bent ran away
        if (anyChord) y = profile(ground, water, river, kind, pin, kindPin, connA, connB);
        foldBends(x, z, y, kind, pin, connA, connB);
        // inside a village the road is never a tunnel or a trestle: it climbs the slope on the ground like the street
        for (int i = 0; i < n; i++)
            if ((i < connA || i >= n - connB) && water[i] <= ground[i] && (kind[i] == EdgePlan.TUNNEL || kind[i] == EdgePlan.BRIDGE)) kind[i] = EdgePlan.GROUND;
        long seed = e.seed();
        double p1 = (seed & 0xffff) / 65536.0 * Math.PI * 2, p2 = ((seed >> 16) & 0xffff) / 65536.0 * Math.PI * 2;
        for (int i = 0; i < n; i++) hw[i] = (float) (1.75 + 0.35 * Math.sin(i * 0.045 + p1) + 0.18 * Math.sin(i * 0.13 + p2));
        // bridges keep one width: 3 deck columns between the parapets on a column-centred line
        for (int i = 0; i < n; i++) {
            int near = 99;
            for (int k = Math.max(0, i - 4); k <= Math.min(n - 1, i + 4); k++) if (kind[k] == EdgePlan.BRIDGE) near = Math.min(near, Math.abs(k - i));
            if (near < 99) hw[i] += (BRIDGE_HW - hw[i]) * Math.min(1f, (5 - near) / 4f);
        }
        EdgePlan plan = new EdgePlan(e, x, z, y, hw, kind, wood, System.nanoTime() - t0);
        plan.shared = shared;
        plan.wet = new boolean[n];
        for (int i = 0; i < n; i++) plan.wet[i] = water[i] > ground[i];
        plan.wetFill = new byte[n];
        for (int i = 0; i < n; i++) if (plan.wet[i]) plan.wetFill[i] = (byte) Math.clamp(Math.round(y[i] - ground[i]), 0, 127);
        bridges(plan, ground, water);
        plan.connA = connA;
        plan.connB = connB;
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
        PropPlanner.dropNearCamps(plan);
        FieldPlanner.dropNearCamps(plan);
        plan.decor.removeIf(d -> d.type() != EdgePlan.PROP && !standable(d));
        return plan;
    }

    private void sampleAt(int i, float[] x, float[] z, float[] ground, float[] water, boolean[] river) {
        TerrainSampler.Sample s = terrain.sample((int) Math.floor(x[i]), (int) Math.floor(z[i]));
        ground[i] = s.ground();
        water[i] = s.water();
        river[i] = s.river();
    }

    static final float BRIDGE_HW = 1.75f;
    /** Samples beyond a stone bridge's ends whose too-high fill becomes a masonry abutment instead of a timber trestle. */
    static final int APPROACH = 14;

    /** Bridges longer than this are stone arches, shorter ones timber. */
    static final int STONE_BRIDGE = 20;
    /** Samples over which the road eases from its own line onto a straightened bridge (more for a sharper turn). */
    static final boolean TRACE_BRIDGES = Boolean.getBoolean("countryroads.traceBridges");
    /** A new turn sharper than this over 4 samples makes an eased line a hairpin (55 kept 45-degree crossings diagonal). */
    static final double KINK = 100;
    /** Dry land at least this many samples long inside a crossing splits it in two. */
    static final int SPLIT_LAND = 8;
    /** Dry trestles shorter than this many samples are filled ground instead. */
    static final int MIN_TRESTLE = 4;
    static final int BRIDGE_EASE = 10, BRIDGE_EASE_TURN = 10, LANDING = 6, WET_REACH = 12;

    /**
     * Each bridge over water becomes a straight line along the nearer world axis, centred on a block column, through
     * the middle of the crossing and as long as its chord; the road eases onto it each side. Diagonal and wobbling
     * bridges made every rail, post and arch a staircase. A crossing that starts or ends too close to the road's end
     * (a village beside the river: the street dock is sample 0) is anchored there instead: the line runs from that end
     * along the axis, and only the other side eases (both ends anchored: the straight chord). Pinned samples (a shared
     * trunk) never move, and a crossing whose new line would pass within 2 blocks of a village building keeps its
     * line. Sample count stays the same (samples on the bridge sit a little closer than a block).
     */
    /** One straight line per crossing: samples a0..b0 lie on it, lo..a0 and b0..hi ease from the old line onto it. */
    record Line(int a, int b, int a0, int b0, int lo, int hi, float ox, float oz, float dx, float dz) {}

    static boolean[] straighten(float[] x, float[] z, byte[] kind, float[] water, float[] ground, float[] pin,
                                java.util.function.BiPredicate<Float, Float> blocked, java.util.function.BiPredicate<Float, Float> wetAt) {
        int n = x.length;
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            int a = i, b = j;
            i = j + 1;
            if (kind[a] != EdgePlan.BRIDGE || b - a < 3) continue;
            boolean wet = false;
            for (int k = a; k <= b; k++) wet |= water[k] > ground[k];
            if (TRACE_BRIDGES && !wet) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT dry " + a + ".." + b + " at " + x[a] + "," + z[a]);
            if (wet && Math.hypot(x[b] - x[a], z[b] - z[a]) >= 1) runs.add(new int[]{a, b});
        }
        // crossings whose eases would overlap become one line (sharing a short gap stretched the road between them)
        List<Line> lines = new ArrayList<>();
        for (int[] r : runs) lines.add(line(x, z, r[0], r[1], wetAt));
        for (int k = 0; k + 1 < lines.size(); ) {
            Line c = lines.get(k), d = lines.get(k + 1);
            // ...unless dry land of SPLIT_LAND+ samples lies between them (an island): merged, the line went past the
            // island through the river and made one 235-block viaduct; split, the road touches down on the island
            int dry = 0, most = 0;
            for (int q = c.b() + 1; q < d.a(); q++) {
                dry = water[q] <= ground[q] ? dry + 1 : 0;
                most = Math.max(most, dry);
            }
            if (c.hi() < d.lo() || most >= SPLIT_LAND) {
                if (c.hi() >= d.lo()) {
                    int mid = (c.b() + d.a()) / 2;
                    int b0 = Math.max(c.b(), Math.min(c.b0(), mid)), a0 = Math.min(d.a(), Math.max(d.a0(), mid + 1));
                    int m2 = (b0 + a0) / 2;
                    lines.set(k, new Line(c.a(), c.b(), c.a0(), b0, c.lo(), Math.clamp(m2, b0, Math.max(b0, c.hi())), c.ox(), c.oz(), c.dx(), c.dz()));
                    lines.set(k + 1, new Line(d.a(), d.b(), a0, d.b0(), Math.clamp(m2 + 1, Math.min(d.lo(), a0), a0), d.hi(), d.ox(), d.oz(), d.dx(), d.dz()));
                }
                k++;
                continue;
            }
            lines.set(k, line(x, z, c.a(), d.b(), wetAt));
            lines.remove(k + 1);
            if (k > 0) k--;
        }
        if (TRACE_BRIDGES) {
            StringBuilder w = new StringBuilder();
            for (int q = 0; q < n; q++) w.append(kind[q] == EdgePlan.BRIDGE ? (water[q] > ground[q] ? 'B' : 'b') : water[q] > ground[q] ? 'W' : '.');
            io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT kinds n=" + n + " " + w);
        }
        if (TRACE_BRIDGES) for (Line l : lines) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT line " + l.a() + ".." + l.b()
            + " at " + x[l.a()] + "," + z[l.a()] + " -> " + x[l.b()] + "," + z[l.b()] + " lo/hi=" + l.lo() + "/" + l.hi());
        boolean[] moved = new boolean[n];
        for (Line l : lines) apply(l, x, z, pin, blocked, moved);
        return moved;
    }

    /**
     * Moves the samples lo..hi onto the line (easing at both ends) unless a real move would cross a pinned sample or come
     * near a village building. Returns whether it moved them.
     */
    private static boolean apply(Line l, float[] x, float[] z, float[] pin, java.util.function.BiPredicate<Float, Float> blocked, boolean[] moved) {
        return apply(l, x, z, pin, blocked, moved, null, null);
    }

    /**
     * dry (chord pass): when not null, an ease sample standing on dry ground never swings out over deep water (deepAt):
     * it kept its ground kind and was filled from the lake bed, or, profiled again, became a bent bridge. The straight
     * part may run on over water (profiled again it joins the crossing); a crossing whose eases cannot stay on land
     * falls back to its plain chord (straight, diagonal).
     */
    private static boolean apply(Line l, float[] x, float[] z, float[] pin, java.util.function.BiPredicate<Float, Float> blocked, boolean[] moved,
                                 boolean[] dry, java.util.function.BiPredicate<Float, Float> deepAt) {
        // a long ease that swings past a building (or onto a shared stretch, or into a kink) gets shorter on that side,
        // then the straight past the water gets shorter, before the crossing gives up
        for (Line t = l; ; ) {
            int bad = applyOnce(t, x, z, pin, blocked, moved, dry, deepAt);
            if (bad == Integer.MIN_VALUE) return true;
            int ea = t.a0() - t.lo(), eb = t.hi() - t.b0();
            if (bad < t.a0() && ea >= 6) t = new Line(t.a(), t.b(), t.a0(), t.b0(), t.a0() - ea / 2, t.hi(), t.ox(), t.oz(), t.dx(), t.dz());
            else if (bad > t.b0() && eb >= 6) t = new Line(t.a(), t.b(), t.a0(), t.b0(), t.lo(), t.b0() + eb / 2, t.ox(), t.oz(), t.dx(), t.dz());
            else {
                if (t.a0() == t.a() && t.b0() == t.b()) return false;
                int a0 = bad <= t.a() ? t.a0() + (t.a() - t.a0() + 1) / 2 : t.a0(), b0 = bad >= t.b() ? t.b0() - (t.b0() - t.b() + 1) / 2 : t.b0();
                if (a0 == t.a0() && b0 == t.b0()) {
                    a0 = t.a0() + (t.a() - t.a0() + 1) / 2;
                    b0 = t.b0() - (t.b0() - t.b() + 1) / 2;
                }
                t = new Line(t.a(), t.b(), a0, b0, Math.max(t.lo(), a0 - (t.a0() - t.lo())), Math.min(t.hi(), b0 + (t.hi() - t.b0())), t.ox(), t.oz(), t.dx(), t.dz());
            }
        }
    }

    /** Integer.MIN_VALUE when the line was applied, else the sample that stopped it. */
    private static int applyOnce(Line l, float[] x, float[] z, float[] pin, java.util.function.BiPredicate<Float, Float> blocked, boolean[] moved,
                                 boolean[] dry, java.util.function.BiPredicate<Float, Float> deepAt) {
        float[] nx = new float[l.hi() - l.lo() + 1], nz = new float[nx.length];
        for (int k = l.lo(); k <= l.hi(); k++) {
            float u = k < l.a0() ? (k - l.lo()) / (float) (l.a0() - l.lo()) : k > l.b0() ? (l.hi() - k) / (float) (l.hi() - l.b0()) : 1f;
            float w = u * u * (3 - 2 * u);
            float px = x[k] + (l.ox() + l.dx() * k - x[k]) * w, pz = z[k] + (l.oz() + l.dz() * k - z[k]) * w;
            float shift = Math.abs(px - x[k]) + Math.abs(pz - z[k]);
            // a snap of half a block at an anchored end is no risk; a real move must stay clear of buildings
            if (shift > 1e-4f && !Float.isNaN(pin[k]) || shift > 0.5f && blocked.test(px, pz)) {
                if (TRACE_BRIDGES) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT skip " + l.a() + ".." + l.b() + " at sample " + k + " ("
                    + px + ", " + pz + ") " + (Float.isNaN(pin[k]) ? "near a building" : "pinned (shared trunk)"));
                return k;
            }
            if (dry != null && dry[k] && (k < l.a0() || k > l.b0()) && shift > 0.5f && deepAt.test(px, pz)) {
                if (TRACE_BRIDGES) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT skip " + l.a() + ".." + l.b() + " at sample " + k + " (" + px + ", " + pz + ") ease onto deep water");
                return k;
            }
            nx[k - l.lo()] = px;
            nz[k - l.lo()] = pz;
        }
        // the eased road must not fold onto itself or kink: a turn of more than KINK degrees over 4 samples that was not
        // there before (a short ease left a kink whose two strands were side by side, 1.5 blocks apart in height)
        int n = x.length;
        for (int k = Math.max(2, l.lo() - 2); k <= Math.min(n - 3, l.hi() + 2); k++) {
            float[] p0 = at(l, x, z, nx, nz, k - 2), p1 = at(l, x, z, nx, nz, k), p2 = at(l, x, z, nx, nz, k + 2);
            double now = turn(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1]), was = turn(x[k - 2], z[k - 2], x[k], z[k], x[k + 2], z[k + 2]);
            if (now > KINK && now > was + 15) {
                if (TRACE_BRIDGES) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STRAIGHT skip " + l.a() + ".." + l.b() + " kink at sample " + k + " (" + Math.round(now) + " deg)");
                return k;
            }
        }
        for (int k = l.lo(); k <= l.hi(); k++) {
            if (Math.abs(nx[k - l.lo()] - x[k]) + Math.abs(nz[k - l.lo()] - z[k]) <= 1e-4f) continue;
            x[k] = nx[k - l.lo()];
            z[k] = nz[k - l.lo()];
            moved[k] = true;
        }
        return Integer.MIN_VALUE;
    }

    private static float[] at(Line l, float[] x, float[] z, float[] nx, float[] nz, int k) {
        return k >= l.lo() && k <= l.hi() ? new float[]{nx[k - l.lo()], nz[k - l.lo()]} : new float[]{x[k], z[k]};
    }

    /** Degrees the line turns at (bx, bz) coming from a and going to c. */
    private static double turn(float ax, float az, float bx, float bz, float cx, float cz) {
        double ux = bx - ax, uz = bz - az, vx = cx - bx, vz = cz - bz, lu = Math.hypot(ux, uz), lv = Math.hypot(vx, vz);
        if (lu < 1e-3 || lv < 1e-3) return 180;
        return Math.toDegrees(Math.acos(Math.clamp((ux * vx + uz * vz) / (lu * lv), -1.0, 1.0)));
    }

    /** The straight line for the crossing a..b (see straighten). */
    private static Line line(float[] x, float[] z, int a, int b, java.util.function.BiPredicate<Float, Float> wetAt) {
        int n = x.length;
        float cx = x[b] - x[a], cz = z[b] - z[a], len = (float) Math.hypot(cx, cz);
        float turn = (float) Math.toDegrees(Math.atan2(Math.min(Math.abs(cx), Math.abs(cz)), Math.max(Math.abs(cx), Math.abs(cz))));
        int ease = BRIDGE_EASE + Math.round(BRIDGE_EASE_TURN * turn / 45f);
        boolean fixA = a - ease < 1, fixB = b + ease > n - 2;
        float step = len / (b - a);
        boolean alongX = Math.abs(cx) >= Math.abs(cz);
        float ax = alongX ? Math.signum(cx) : 0, az = alongX ? 0 : Math.signum(cz);
        // through the middle (free), from the anchored end (one end fixed), or the chord (both fixed)
        float ox, oz, dx, dz;
        if (fixA && fixB) {
            dx = cx / (b - a);
            dz = cz / (b - a);
            ox = x[a] - dx * a;
            oz = z[a] - dz * a;
        } else {
            int anchor = fixA ? a : fixB ? b : -1;
            float mid = anchor >= 0 ? anchor : (a + b) / 2f;
            ox = anchor >= 0 ? x[anchor] : (x[a] + x[b]) / 2;
            oz = anchor >= 0 ? z[anchor] : (z[a] + z[b]) / 2;
            if (alongX) oz = (float) Math.floor(oz) + 0.5f;
            else ox = (float) Math.floor(ox) + 0.5f;
            dx = ax * step;
            dz = az * step;
            ox -= dx * mid;
            oz -= dz * mid;
        }
        // the straight runs on while the water under the line itself goes on (a road bending along the river), then
        // LANDING samples more before the road turns (a turn right at the shore put a raised bridge's end on the bend)
        int ea = a, eb = b;
        while (!fixA && ea > 1 && a - ea < WET_REACH && wetAt.test(ox + dx * (ea - 1), oz + dz * (ea - 1))) ea--;
        while (!fixB && eb < n - 2 && eb - b < WET_REACH && wetAt.test(ox + dx * (eb + 1), oz + dz * (eb + 1))) eb++;
        int a0 = fixA ? a : Math.max(1, ea - LANDING), b0 = fixB ? b : Math.min(n - 2, eb + LANDING);
        // a free end that lands far to the side of the old line eases over more samples (2 per block of shift), and over
        // more where the road leaves the bridge at a sharp angle (a tight bend there folded the road onto itself)
        float offA = (float) Math.hypot(ox + dx * a0 - x[a0], oz + dz * a0 - z[a0]), offB = (float) Math.hypot(ox + dx * b0 - x[b0], oz + dz * b0 - z[b0]);
        int bendA = Math.round(turnFrom(x, z, a0, -1, ax, az) / 90f * 24), bendB = Math.round(turnFrom(x, z, b0, 1, ax, az) / 90f * 24);
        int lo = fixA ? a : Math.max(1, a0 - Math.max(Math.max(ease, bendA), Math.round(2 * offA))),
            hi = fixB ? b : Math.min(n - 2, b0 + Math.max(Math.max(ease, bendB), Math.round(2 * offB)));
        return new Line(a, b, a0, b0, lo, hi, ox, oz, dx, dz);
    }

    /** Water at least this deep under an ease sample that stood on dry ground stops the chord pass's ease (see apply). */
    static final int EASE_DEEP = Integer.getInteger("countryroads.easeDeep", 1);

    private boolean deepAt(float px, float pz) {
        TerrainSampler.Sample smp = terrain.sample((int) Math.floor(px), (int) Math.floor(pz));
        return smp.wet() && smp.depth() >= EASE_DEEP;
    }

    /** Wet bridge runs more than half a block off the straight line between their ends, put on that line. */
    static boolean[] chordBridges(float[] x, float[] z, byte[] kind, float[] water, float[] ground, float[] pin,
                                  java.util.function.BiPredicate<Float, Float> blocked, java.util.function.BiPredicate<Float, Float> wetAt,
                                  java.util.function.BiPredicate<Float, Float> deepAt) {
        int n = x.length;
        boolean[] moved = new boolean[n];
        boolean[] dry = new boolean[n];
        for (int k = 0; k < n; k++) dry[k] = !wetAt.test(x[k], z[k]);
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            int a = i, b = j;
            i = j + 1;
            // dry trestles too (from 4 samples): a ravine bridge is as much a bridge as a river one
            if (kind[a] != EdgePlan.BRIDGE || b - a < 2) continue;
            boolean wet = false;
            for (int k = a; k <= b; k++) wet |= water[k] > ground[k];
            float cx = x[b] - x[a], cz = z[b] - z[a], len = (float) Math.hypot(cx, cz), dev = 0;
            if (!wet && b - a < 3 || len < 1) continue;
            for (int k = a; k <= b; k++) dev = Math.max(dev, Math.abs((x[k] - x[a]) * cz - (z[k] - z[a]) * cx) / len);
            if (dev <= 0.5f && (Math.abs(cx) < 0.05f * len || Math.abs(cz) < 0.05f * len)) continue;
            // the axis line first (the road either side eases, without a new profile: heights stay, the painter fills),
            // the plain chord between the run's own ends only where that would come near a building
            if (TRACE_BRIDGES) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-CHORD " + a + ".." + b + " of " + n + " dev=" + dev + " c=" + cx + "," + cz + " line=" + line(x, z, a, b, wetAt));
            if (apply(line(x, z, a, b, wetAt), x, z, pin, blocked, moved, dry, deepAt)) continue;
            if (dev <= 0.5f) continue;
            boolean ok = true;
            for (int k = a + 1; k < b && ok; k++) {
                float t = (k - a) / (float) (b - a);
                ok = Float.isNaN(pin[k]) && !blocked.test(x[a] + cx * t, z[a] + cz * t);
            }
            if (!ok) continue;
            for (int k = a + 1; k < b; k++) {
                float t = (k - a) / (float) (b - a);
                x[k] = x[a] + cx * t;
                z[k] = z[a] + cz * t;
                moved[k] = true;
            }
        }
        return moved;
    }

    /**
     * Whether (x, z) is within TRUNK_CLEAR of an earlier road's centreline: a straightened line moved next to another road
     * met it at a different height (the planner does not match heights where roads cross).
     */
    private static boolean nearTrunk(List<Trunk> trunks, float x, float z) {
        for (Trunk t : trunks)
            for (int k = 0; k < t.p.length(); k++) {
                float dx = t.p.x[k] - x, dz = t.p.z[k] - z;
                if (dx * dx + dz * dz < TRUNK_CLEAR * TRUNK_CLEAR) return true;
            }
        return false;
    }

    static final float TRUNK_CLEAR = 4.5f;
    /** Samples beyond a stone bridge's ends that must be free of pinned heights for its deck to be raised. */
    static final int RAISE_CLEAR = 12;
    /** A stone bridge needs at least this many samples (a 2-3 sample stone bridge was a lone arch). */
    static final int MIN_STONE = 8;
    /** Samples either side of a pinned sample graded at CONN_GRADE. */
    static final int PIN_EASE = 6;

    /** Degrees between the axis (ax, az), pointed away from the bridge, and the old line's heading over 12 samples from k. */
    private static float turnFrom(float[] x, float[] z, int k, int dir, float ax, float az) {
        int e = Math.clamp(k + 12 * dir, 0, x.length - 1);
        float vx = x[e] - x[k], vz = z[e] - z[k], len = (float) Math.hypot(vx, vz);
        if (len < 1e-3f) return 0;
        float cos = (vx * ax * dir + vz * az * dir) / len;
        return (float) Math.toDegrees(Math.acos(Math.clamp(cos, -1f, 1f)));
    }

    /** Style, wood pair and arch rise per bridge run, decided once per crossing so the whole bridge matches. */
    private void bridges(EdgePlan p, float[] ground, float[] water) {
        int n = p.length();
        p.bridge = new byte[n];
        p.bridgeWood = new byte[n];
        p.bridgeRise = new byte[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && p.kind[j + 1] == p.kind[i]) j++;
            if (p.kind[i] == EdgePlan.BRIDGE) {
                // headroom over the crossing's usual water level (a pond up on the bank must not flatten the arches);
                // over the lowest ground on a dry trestle
                int len = j - i + 1, clear;
                it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap levels = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
                float low = Float.MAX_VALUE;
                for (int k = i; k <= j; k++) {
                    if (water[k] > ground[k]) levels.addTo((int) water[k], 1);
                    low = Math.min(low, ground[k]);
                }
                int level = (int) low, best = 0;
                for (var en : levels.int2IntEntrySet()) if (en.getIntValue() > best) {
                    best = en.getIntValue();
                    level = en.getIntKey();
                }
                clear = Math.round(p.y[(i + j) / 2]) - level - 2;
                // a raised deck (planned as stone before its dry ends were trimmed off) stays stone
                byte style = (len > STONE_BRIDGE || clear >= 4 && levels.size() > 0) && len >= MIN_STONE ? EdgePlan.STONE : EdgePlan.TIMBER;
                int mid = (i + j) / 2;
                byte pair = Palette.bridgeWoods(terrain.biome((int) p.x[mid], (int) water[mid], (int) p.z[mid]));
                // the raised approaches too: where their fill is too high for an embankment the painter walls them in stone
                int from = style == EdgePlan.STONE ? Math.max(0, i - APPROACH) : i, to = style == EdgePlan.STONE ? Math.min(n - 1, j + APPROACH) : j;
                for (int k = from; k <= to; k++) {
                    if (p.kind[k] != EdgePlan.BRIDGE && p.kind[k] != EdgePlan.GROUND || p.kind[k] == EdgePlan.GROUND && p.bridge[k] == EdgePlan.STONE) continue;
                    p.bridge[k] = style;
                    p.bridgeWood[k] = pair;
                    p.bridgeRise[k] = (byte) Math.clamp(clear, 0, 12);
                }
            }
            i = j + 1;
        }
    }

    /** Signpost at each village end (pointing to the other village), lamp posts on the last 30 blocks into each village. */
    void decorate(EdgePlan p) {
        int n = p.length();
        if (n < 40) return;
        int dist = Math.round(n / 10f) * 10;
        // posts count from where the connector leaves the village (the street end itself is inside it)
        int a = p.connA, b = p.connB;
        signEither(p, a + 4, 1, p.nameB, dist, p.nameA);
        signEither(p, n - 1 - b - 4, -1, p.nameA, dist, p.nameB);
        for (int k = 1; k <= 3; k++) {
            post(p, a + 10 * k, 1, k % 2 == 0 ? 1 : -1, EdgePlan.LAMP, "", "", "");
            post(p, n - 1 - b - 10 * k, -1, k % 2 == 0 ? 1 : -1, EdgePlan.LAMP, "", "", "");
        }
        // orchard verge: a few blossoming fruit trees on one side of the last stretch into each village
        long oh = p.edge.seed() ^ 0x0C4A2DL;
        for (int end = 0; end < 2; end++) {
            int side = ((oh >>> (end + 5)) & 1) == 0 ? 1 : -1;
            for (int k = 0; k < 4; k++) {
                int i = 14 + 9 * k + (int) ((oh >>> (8 + k * 4 + end * 16)) & 3);
                if (i >= n / 2) break;
                if (end == 0) post(p, a + i, 1, side, EdgePlan.ORCHARD, "", "", "", 4.5f, 2);
                else post(p, n - 1 - b - i, -1, side, EdgePlan.ORCHARD, "", "", "", 4.5f, 2);
            }
        }
        FieldPlanner.plan(p, terrain);
        PropPlanner.plan(p, terrain);
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
            for (int k = s; k <= e && ok; k++)
                for (Field f : p.fields) ok &= !f.covers(p.x[k], p.z[k], 5);
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
            if (Math.abs(p.y[b] - p.y[a]) > 3.2f) continue;
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
            if (type != EdgePlan.ORCHARD) {
                // the arm reaches one column toward the road: never over a road column (a hanging lantern would be at head height)
                double ax = x + toRoad.getStepX() + 0.5, az = z + toRoad.getStepZ() + 0.5;
                boolean over = false;
                for (int j = Math.max(0, i - 8); j <= Math.min(p.length() - 1, i + 8); j++) over |= Math.hypot(ax - p.x[j], az - p.z[j]) < p.hw[j] + 0.3;
                if (over) continue;
            }
            p.decor.add(new EdgePlan.Decor(x, z, p.y[i], type, toRoad, along, f1, f2, back));
            return;
        }
    }

    /** The connector's cells as a smooth line one block apart, from the dock to the exit. */
    private static List<double[]> connectorLine(Docking.Connector c) {
        List<double[]> pts = new ArrayList<>();
        for (int[] cell : c.cells()) pts.add(new double[]{cell[0] + 0.5, cell[1] + 0.5});
        for (int k = 0; k < 2; k++) pts = chaikin(pts);
        return resample(pts, 1.0);
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
        return profile(ground, water, river, kind, pin, pinKind, 0, 0);
    }

    /** Steepest grade (blocks per sample) of a connector inside a village: a step about every block and a third. */
    static final float CONN_GRADE = 0.75f;

    static float[] profile(float[] ground, float[] water, boolean[] river, byte[] kind, float[] pin, byte[] pinKind, int connA, int connB) {
        int n = ground.length;
        // next to a pinned stretch (a shared road's fixed heights) the road may climb like a village connector: a bridge
        // ending a few samples before a shared stretch at water level could not come down to it at the normal grade
        boolean[] pinNear = new boolean[n];
        for (int i = 1; i < n - 1; i++)
            if (!Float.isNaN(pin[i])) for (int k = Math.max(0, i - PIN_EASE); k <= Math.min(n - 1, i + PIN_EASE); k++) pinNear[k] = true;
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
        // a boardwalk touching a bridge is part of that crossing: one deck, one height (it stepped down into a raft)
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < n; i++)
                if (kind[i] == EdgePlan.BOARDWALK && (i > 0 && kind[i - 1] == EdgePlan.BRIDGE || i + 1 < n && kind[i + 1] == EdgePlan.BRIDGE)) {
                    kind[i] = EdgePlan.BRIDGE;
                    changed = true;
                }
        }

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
                // stone bridges stand three higher than timber so their arches clear the water
                // ...unless a pinned stretch (a shared road's fixed heights) or a village connector (it climbs from the street)
                // starts too close to either end to ramp down to (a connector climbed 62 -> 68 with a 2-block step)
                boolean pinned = false;
                for (int k = Math.max(0, i - RAISE_CLEAR); k <= Math.min(n - 1, j + RAISE_CLEAR) && !pinned; k++)
                    pinned = !Float.isNaN(pin[k]) && k > 0 && k < n - 1;
                // a connector needs twice the room: the deck's dry end stays level up to it and the connector eases from
                // the street ground (a bridge 17 samples before a connector still left it a 2-block step)
                pinned |= i < connA + 2 * RAISE_CLEAR || j >= n - connB - 2 * RAISE_CLEAR;
                if (kind[i] == EdgePlan.BRIDGE && j - i + 1 > STONE_BRIDGE && !pinned) {
                    deck += 3;
                    for (int k = i; k <= j; k++) base[k] = deck;
                }
                for (int k = i; k <= j; k++) y[k] = deck;
            }
            i = j + 1;
        }
        for (int pass = 0; pass < 6; pass++) {
            for (int i = 0; i < n; i++) if (kind[i] != EdgePlan.GROUND) y[i] = Math.max(y[i], base[i]);
            for (int i = 0; i < n; i++) if (!Float.isNaN(pin[i])) y[i] = pin[i];
            // a steeper grade is allowed on the last stretch into a village, so the road climbs into the street
            for (int i = 1; i < n; i++) {
                float g = i < connA || i >= n - connB || pinNear[i] ? CONN_GRADE : i < 24 || i > n - 25 ? 0.5f : MAX_GRADE;
                if (Float.isNaN(pin[i])) y[i] = Math.clamp(y[i], y[i - 1] - g, y[i - 1] + g);
            }
            for (int i = n - 2; i >= 0; i--) {
                float g = i < connA || i >= n - connB || pinNear[i] ? CONN_GRADE : i < 24 || i > n - 25 ? 0.5f : MAX_GRADE;
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
        if (!Boolean.getBoolean("countryroads.noCutCap")) capCuts(y, ground, water, kind, pin, connA, connB);
        // trestle samples that joined a crossing above (the climb to a raised stone deck) are part of the bridge: the
        // whole crossing is level and the climb moves onto the ground before it. Until nothing changes: new trestle
        // samples from the last round joined two runs at different deck heights (a 3-block step inside one bridge)
        for (int round = 0; round < 12; round++) {
            boolean changed = false;
            // a boardwalk the crossing grew into is part of it (it stepped up two blocks a sample into the deck)
            for (boolean more = true; more; ) {
                more = false;
                for (int i = 0; i < n; i++)
                    if (kind[i] == EdgePlan.BOARDWALK && Float.isNaN(pin[i]) && (i > 0 && kind[i - 1] == EdgePlan.BRIDGE || i + 1 < n && kind[i + 1] == EdgePlan.BRIDGE)) {
                        kind[i] = EdgePlan.BRIDGE;
                        more = changed = true;
                    }
            }
            for (int i = 0; i < n; ) {
                int j = i;
                while (j + 1 < n && kind[j + 1] == kind[i]) j++;
                if (kind[i] == EdgePlan.BRIDGE) {
                    boolean wet = false;
                    float deck = Float.NEGATIVE_INFINITY;
                    for (int k = i; k <= j; k++) {
                        wet |= water[k] > ground[k];
                        deck = Math.max(deck, y[k]);
                    }
                    for (int k = i; k <= j && wet; k++)
                        if (Float.isNaN(pin[k]) && y[k] != deck) {
                            y[k] = deck;
                            changed = true;
                        }
                }
                i = j + 1;
            }
            if (!changed) break;
            for (int i = 1; i < n; i++)
                if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i])) y[i] = Math.max(y[i], y[i - 1] - (pinNear[i] || i < connA || i >= n - connB ? CONN_GRADE : i < 24 || i > n - 25 ? 0.5f : MAX_GRADE));
            for (int i = n - 2; i >= 0; i--)
                if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i])) y[i] = Math.max(y[i], y[i + 1] - (pinNear[i] || i < connA || i >= n - connB ? CONN_GRADE : i < 24 || i > n - 25 ? 0.5f : MAX_GRADE));
            for (int i = 0; i < n; i++)
                if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i]) && y[i] > ground[i] + 5) kind[i] = EdgePlan.BRIDGE;
        }
        // land of SPLIT_LAND+ samples inside a crossing (an island between two channels) splits it into two bridges: the
        // land is ground again, ramping down from each deck at the grade limit (one viaduct ran 137 blocks over both)
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == EdgePlan.BRIDGE) {
                for (int k = i + 1; k < j; ) {
                    if (water[k] > ground[k]) {
                        k++;
                        continue;
                    }
                    int e = k;
                    while (e + 1 < j && water[e + 1] <= ground[e + 1]) e++;
                    boolean free = true;
                    for (int q = k; q <= e; q++) free &= Float.isNaN(pin[q]);
                    // both bridges left must be real ones (a split that left a 2-sample crossing beside a raised deck made a step)
                    if (e - k + 1 >= SPLIT_LAND && free && k - i >= MIN_TRESTLE && j - e >= MIN_TRESTLE) {
                        float left = y[k - 1], right = y[e + 1];
                        for (int q = k; q <= e; q++) {
                            kind[q] = EdgePlan.GROUND;
                            y[q] = Math.max(ground[q], Math.max(left - MAX_GRADE * (q - k + 1), right - MAX_GRADE * (e - q + 1)));
                        }
                    }
                    k = e + 1;
                }
            }
            i = j + 1;
        }
        // a dry trestle shorter than MIN_TRESTLE samples is no bridge (a lone post over a dip): it is filled ground
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == EdgePlan.BRIDGE && j - i + 1 < MIN_TRESTLE) {
                boolean wet = false;
                for (int k = i; k <= j; k++) wet |= water[k] > ground[k];
                if (!wet) for (int k = i; k <= j; k++) if (Float.isNaN(pin[k])) kind[k] = EdgePlan.GROUND;
            }
            i = j + 1;
        }
        // a crossing ends where its water ends: dry samples at either end (the bank, the level part of a raised deck's
        // approach) are ground again, built up as an embankment or, too high for one, a masonry abutment. A road that
        // turned along the bank right after the water put arches on the bend.
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == EdgePlan.BRIDGE) {
                int a = i, b = j;
                while (a < b - 2 && water[a] <= ground[a] && Float.isNaN(pin[a])) a++;
                while (b > a + 2 && water[b] <= ground[b] && Float.isNaN(pin[b])) b--;
                // only where the end meets ordinary ground (a level dry end beside a boardwalk left a 4-block step)
                if (water[a] > ground[a] || water[b] > ground[b]) {
                    if (i == 0 || kind[i - 1] == EdgePlan.GROUND) for (int k = i; k < a; k++) kind[k] = EdgePlan.GROUND;
                    if (j == n - 1 || kind[j + 1] == EdgePlan.GROUND) for (int k = b + 1; k <= j; k++) kind[k] = EdgePlan.GROUND;
                }
            }
            i = j + 1;
        }
        // a stream only MIN_TRESTLE-1 samples wide is no bridge either: the road crosses it on its own embankment, as wet
        // specks always did (a 2-sample bridge at water + 3 stood three blocks under the next bridge's raised approach)
        boolean tiny = false;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) j++;
            if (kind[i] == EdgePlan.BRIDGE && j - i + 1 < MIN_TRESTLE) {
                boolean free = true;
                for (int k = i; k <= j; k++) free &= Float.isNaN(pin[k]);
                if (free) {
                    for (int k = i; k <= j; k++) kind[k] = EdgePlan.GROUND;
                    tiny = true;
                }
            }
            i = j + 1;
        }
        // ground next to a deck climbs to it within the grade (raise only, so no deck or pin is cut into)
        if (tiny)
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i < n; i++)
                    if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i])) y[i] = Math.max(y[i], y[i - 1] - (pinNear[i] || i < connA || i >= n - connB ? CONN_GRADE : MAX_GRADE));
                for (int i = n - 2; i >= 0; i--)
                    if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i])) y[i] = Math.max(y[i], y[i + 1] - (pinNear[i] || i < connA || i >= n - connB ? CONN_GRADE : MAX_GRADE));
            }
        return y;
    }

    /** Cross-section stations every NUDGE_STEP samples; shifts tried up to NUDGE_MAX blocks either way. */
    /** Two roads whose centrelines are closer than this overlap (two half-widths of about 2). */
    static final double OVERLAP = 4.5;
    /** Samples off the shared line that still count as one shared stretch. */
    static final int SHARE_GAP = Integer.getInteger("countryroads.shareGap", 20);
    static final int NUDGE_STEP = 8, NUDGE_MAX = 12, NUDGE_TRY = 3, NUDGE_KEEP = 24;
    /** Station cost (squared blocks of hillside the cut slope cannot meet) above which shifts are tried at all. */
    static final double NUDGE_FROM = 6;

    /**
     * Side-hill nudge: where the road runs along a slope steeper than its cut can meet, slide the centreline sideways
     * (downhill, onto the flatter foot of the slope) by up to NUDGE_MAX blocks. Each station looks at the natural ground
     * across the road; a shift is chosen by the hillside left standing above a 1.5-per-block cut slope, plus the drop
     * a fill would need, plus a small cost per block moved. Shifts are smoothed along the road and eased in. Pinned
     * (shared trunk) and connector samples never move, nor does anything near them; wet stations are not entered.
     */
    void nudgeOffFlanks(List<double[]> line, float[] pinY, int connA, int connB, List<Trunk> trunks) {
        int L = line.size();
        if (L < 4 * NUDGE_STEP) return;
        int stations = L / NUDGE_STEP + 1;
        double[] shift = new double[stations];
        boolean[] locked = new boolean[stations];
        boolean anyShift = false;
        for (int k = 0; k < stations; k++) {
            int i = Math.min(L - 1, k * NUDGE_STEP);
            boolean lock = i < connA + NUDGE_STEP || i >= L - connB - NUDGE_STEP;
            for (int j = Math.max(0, i - 2 * NUDGE_STEP); j <= Math.min(L - 1, i + 2 * NUDGE_STEP) && !lock; j++) lock = !Float.isNaN(pinY[j]);
            // near another road (a trunk this one meets or runs beside) the line stays: both must meet at one height
            double[] here0 = line.get(i);
            for (Trunk t : trunks)
                for (int q = 0; q < t.p.length() && !lock; q += 4)
                    lock = Math.abs(t.p.x[q] - here0[0]) < NUDGE_KEEP && Math.abs(t.p.z[q] - here0[1]) < NUDGE_KEEP;
            locked[k] = lock;
            if (lock) continue;
            double[] nrm = normal(line, i);
            double[] p = line.get(i);
            double here = flankCost(p[0], p[1], nrm);
            if (here < NUDGE_FROM) continue;
            double best = here, bestS = 0;
            for (int sIdx = -NUDGE_MAX / NUDGE_TRY; sIdx <= NUDGE_MAX / NUDGE_TRY; sIdx++) {
                int sft = sIdx * NUDGE_TRY;
                if (sft == 0) continue;
                double cx = p[0] + nrm[0] * sft, cz = p[1] + nrm[1] * sft;
                if (terrain.sample((int) Math.floor(cx), (int) Math.floor(cz)).wet()) continue;
                double c = flankCost(cx, cz, nrm) + 0.08 * sft * sft;
                if (c < best - 1e-9 || c == best && Math.abs(sft) < Math.abs(bestS)) {
                    best = c;
                    bestS = sft;
                }
            }
            shift[k] = bestS;
            anyShift |= bestS != 0;
        }
        if (!anyShift) return;
        // smooth: a little averaging, then no more than NUDGE_TRY of change between stations (no zig-zags)
        double[] sm = new double[stations];
        for (int k = 0; k < stations; k++) {
            if (locked[k]) continue;
            double sum = 0, w = 0;
            for (int d = -2; d <= 2; d++) {
                int q = k + d;
                if (q < 0 || q >= stations) continue;
                double wt = 3 - Math.abs(d);
                sum += (locked[q] ? 0 : shift[q]) * wt;
                w += wt;
            }
            sm[k] = sum / w;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 1; k < stations; k++) sm[k] = Math.clamp(sm[k], sm[k - 1] - NUDGE_TRY, sm[k - 1] + NUDGE_TRY);
            for (int k = stations - 2; k >= 0; k--) sm[k] = Math.clamp(sm[k], sm[k + 1] - NUDGE_TRY, sm[k + 1] + NUDGE_TRY);
            for (int k = 0; k < stations; k++) if (locked[k]) sm[k] = 0;
        }
        List<double[]> normals = new ArrayList<>(L);
        for (int i = 0; i < L; i++) normals.add(normal(line, i));
        for (int i = 0; i < L; i++) {
            double u = i / (double) NUDGE_STEP;
            int k0 = Math.min(stations - 1, (int) Math.floor(u)), k1 = Math.min(stations - 1, k0 + 1);
            double t = u - k0;
            t = t * t * (3 - 2 * t);
            double off = sm[k0] + (sm[k1] - sm[k0]) * t;
            if (off == 0 || !Float.isNaN(pinY[i]) || i < connA || i >= L - connB) continue;
            double[] p = line.get(i), nrm = normals.get(i);
            line.set(i, new double[]{p[0] + nrm[0] * off, p[1] + nrm[1] * off});
        }
    }

    /** Unit normal (to the left of travel) at sample i, from the line around it. */
    private static double[] normal(List<double[]> line, int i) {
        int a = Math.max(0, i - 4), b = Math.min(line.size() - 1, i + 4);
        double tx = line.get(b)[0] - line.get(a)[0], tz = line.get(b)[1] - line.get(a)[1], len = Math.hypot(tx, tz);
        if (len < 1e-6) return new double[]{1, 0};
        return new double[]{-tz / len, tx / len};
    }

    /**
     * Earthworks a road centred at (cx, cz) would need across its width: on each side, hillside standing above a cut slope
     * of 1.5 per block from the road edge (2 blocks out) and ground falling away further than a 4-block fill, squared
     * and summed over two distances (5 and 9 blocks out). The road surface is taken as the ground at the centre.
     */
    double flankCost(double cx, double cz, double[] nrm) {
        int g0 = terrain.sample((int) Math.floor(cx), (int) Math.floor(cz)).ground();
        double cost = 0;
        for (int side = -1; side <= 1; side += 2)
            for (int d : new int[]{5, 9}) {
                var smp = terrain.sample((int) Math.floor(cx + nrm[0] * side * d), (int) Math.floor(cz + nrm[1] * side * d));
                if (smp.wet()) continue;
                double rise = smp.ground() - g0 - 1.5 * (d - 2), drop = g0 - smp.ground() - 4 - (d - 2);
                if (rise > 0) cost += rise * rise;
                if (drop > 0) cost += 0.5 * drop * drop;
            }
        return cost;
    }

    /** Deepest cut (blocks below the natural ground) a ground road may sit in; deeper, short stretches climb instead. */
    static final int MAX_CUT = 4;
    /** Grade of the ramps that lift a road out of a would-be deep cut. */
    static final float CUT_RAMP = 0.5f;

    /**
     * A ground road the grade limit left deep below the land (a short hump the profile could not climb, too short to be
     * a tunnel) would be a slot with sheer walls: lift it to MAX_CUT below the ground and ramp the road up to it on both
     * sides (raising only, never lowering, never moving pinned samples).
     */
    /**
     * Two stretches of one road a few samples apart that touch (the inside of a sharp bend) were painted side by side at
     * the heights of different samples: a 1.5-block step between neighbouring columns. Their heights are brought within
     * FOLD_STEP of each other (the lower one raised, then the grade passes again, raise only).
     */
    static void foldBends(float[] x, float[] z, float[] y, byte[] kind, float[] pin, int connA, int connB) {
        int n = y.length;
        for (int round = 0; round < 8; round++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 3; j < Math.min(n, i + 16); j++) {
                    if (kind[i] != EdgePlan.GROUND || kind[j] != EdgePlan.GROUND) continue;
                    float d = (float) Math.hypot(x[j] - x[i], z[j] - z[i]);
                    // touching only across a sharp turn: the road's direction at the two samples differs by over 60 deg
                    if (d > FOLD_REACH || i + 1 >= n || j + 1 >= n) continue;
                    float ax = x[i + 1] - x[i], az = z[i + 1] - z[i], bx = x[j + 1] - x[j], bz = z[j + 1] - z[j];
                    float la = (float) Math.hypot(ax, az), lb = (float) Math.hypot(bx, bz);
                    if (la < 1e-3f || lb < 1e-3f || (ax * bx + az * bz) / (la * lb) > FOLD_COS) continue;
                    int lo = y[i] < y[j] ? i : j, hi = lo == i ? j : i;
                    if (y[hi] - y[lo] <= FOLD_STEP || !Float.isNaN(pin[lo]) || lo < connA || lo >= n - connB) continue;
                    y[lo] = y[hi] - FOLD_STEP;
                    changed = true;
                }
            }
            if (!changed) break;
            for (int i = 1; i < n; i++)
                if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i]) && i >= connA && i < n - connB) y[i] = Math.max(y[i], y[i - 1] - MAX_GRADE);
            for (int i = n - 2; i >= 0; i--)
                if (kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i]) && i >= connA && i < n - connB) y[i] = Math.max(y[i], y[i + 1] - MAX_GRADE);
        }
    }

    static final float FOLD_REACH = 3.5f, FOLD_STEP = 0.6f, FOLD_COS = 0.5f;

    static void capCuts(float[] y, float[] ground, float[] water, byte[] kind, float[] pin, int connA, int connB) {
        int n = y.length;
        for (int i = connA; i < n - connB; ) {
            if (!capFree(i, kind, pin, water, ground) || y[i] >= ground[i] - MAX_CUT) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n - connB && capFree(j + 1, kind, pin, water, ground) && y[j + 1] < ground[j + 1] - MAX_CUT) j++;
            // lift this stretch and ramp the road up to it; keep the lift only when no ramp sample ends up higher above
            // its own ground than a fill can hold (the ramp ran out over a valley as a 125-block dry viaduct)
            float[] t = y.clone();
            for (int k = i; k <= j; k++) t[k] = ground[k] - MAX_CUT;
            // the lifted stretch keeps the grade (raise only): ground - MAX_CUT copied a steep slope's 2-block steps
            for (int k = i + 1; k <= j; k++) t[k] = Math.max(t[k], t[k - 1] - MAX_GRADE);
            for (int k = j - 1; k >= i; k--) t[k] = Math.max(t[k], t[k + 1] - MAX_GRADE);
            boolean ok = true;
            for (int k = i; k <= j && ok; k++) ok = t[k] <= ground[k] + CAP_FILL;
            for (int k = j + 1; k < n && ok; k++) {
                if (t[k] >= t[k - 1] - CUT_RAMP) break;
                // the ramp would have to lift a tunnel, bridge, pinned or connector sample: a step there, keep the cut
                if (k >= n - connB || !capFree(k, kind, pin, water, ground)) ok = false;
                t[k] = t[k - 1] - CUT_RAMP;
                ok = t[k] <= ground[k] + CAP_FILL;
            }
            for (int k = i - 1; k >= 0 && ok; k--) {
                if (t[k] >= t[k + 1] - CUT_RAMP) break;
                if (k < connA || !capFree(k, kind, pin, water, ground)) ok = false;
                t[k] = t[k + 1] - CUT_RAMP;
                ok = t[k] <= ground[k] + CAP_FILL;
            }
            if (ok) System.arraycopy(t, 0, y, 0, n);
            i = j + 1;
        }
    }

    /** Most a cap's ramp may stand above the land (a fill the painter builds); higher, the cut stays. */
    static final int CAP_FILL = 3;

    private static boolean capFree(int i, byte[] kind, float[] pin, float[] water, float[] ground) {
        return kind[i] == EdgePlan.GROUND && Float.isNaN(pin[i]) && water[i] <= ground[i];
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
