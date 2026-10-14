package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * How much a road bit into the land beside it. Cross-sections every STEP samples: the built terrain top and the
 * generator's own ground (before roads, villages or trees) on columns out to OUT blocks either side of the centreline.
 * centreCut = natural ground minus the road's surface at the centre (ground roads only, tunnels excluded).
 * face = the tallest one-column rise in the built cross-section beside the road, minus the tallest one in the natural one
 * (a face the road made, not a cliff the mountain already had). Lists the worst sites.
 */
final class CutCheck {
    static final int STEP = 2, OUT = 14, WORST = 12, FACE_LIMIT = 4, SHOTS = 4;

    /** -Dcountryroads.cutRef=FILE: with noPaint, the built tops are written there; without, they are read as the natural land. */
    static final String REF = System.getProperty("countryroads.cutRef");

    private CutCheck() {}

    private static it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap loadRef() {
        var map = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        map.defaultReturnValue(Integer.MIN_VALUE);
        if (REF == null || io.github.profetgit.countryroads.paint.RoadPainter.NO_PAINT) return map;
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of(REF))))) {
            int k = in.readInt();
            for (int q = 0; q < k; q++) map.put(in.readLong(), in.readInt());
        } catch (java.io.IOException e) {
            CountryRoads.LOG.warn("CR-CUT no reference " + REF + ": " + e);
        }
        return map;
    }

    private static void saveRef(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap map) {
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Path.of(REF))))) {
            out.writeInt(map.size());
            for (var e : map.long2IntEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeInt(e.getIntValue());
            }
        } catch (java.io.IOException e) {
            CountryRoads.LOG.warn("CR-CUT cannot write reference " + REF + ": " + e);
        }
    }

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private record Site(int x, int y, int z, int face, int centreCut, int faceBuilt, int faceNatural, String side, float nx, float nz) {}

    static int cuts(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        List<int[]> todo = new ArrayList<>();
        List<EdgePlan> plans = new ArrayList<>();
        int[] cursor = {-1};
        List<Site> sites = new ArrayList<>();
        Map<Integer, Integer> faceHist = new TreeMap<>(), cutHist = new TreeMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        var ref = loadRef();
        boolean writing = REF != null && io.github.profetgit.countryroads.paint.RoadPainter.NO_PAINT;
        var written = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        java.util.function.IntBinaryOperator natural = (x, z) -> {
            int r = ref.get(io.github.profetgit.countryroads.world.StreetBands.pack(x, z));
            return r != Integer.MIN_VALUE ? r : n.terrain.sample(x, z).ground();
        };
        DevCommands.queue(() -> {
            if (cursor[0] < 0) {
                // plans are read when the queue reaches this task (an earlier pregen may still be running when the command is typed)
                java.util.Set<io.github.profetgit.countryroads.plan.Edge> edges = new java.util.LinkedHashSet<>();
                for (int rx = (at.getX() - radius) >> 9; rx <= (at.getX() + radius) >> 9; rx++)
                    for (int rz = (at.getZ() - radius) >> 9; rz <= (at.getZ() + radius) >> 9; rz++)
                        edges.addAll(n.edgesInRegion(rx, rz));
                for (var e : edges) {
                    EdgePlan p = n.plan(e).join();
                    if (p != null) plans.add(p);
                }
                for (int pi = 0; pi < plans.size(); pi++) {
                    EdgePlan p = plans.get(pi);
                    for (int i = 0; i < p.length(); i += STEP)
                        if (Math.abs(p.x[i] - at.getX()) <= radius && Math.abs(p.z[i] - at.getZ()) <= radius) todo.add(new int[]{pi, i});
                }
                cursor[0] = 0;
            }
            long until = System.nanoTime() + 40_000_000L;
            while (cursor[0] < todo.size() && System.nanoTime() < until) {
                int[] t = todo.get(cursor[0]++);
                EdgePlan p = plans.get(t[0]);
                int i = t[1];
                if (p.kind[i] != EdgePlan.GROUND || i < p.connA || i >= p.length() - p.connB) continue;
                boolean nearDeck = false;
                for (int q = Math.max(0, i - 10); q <= Math.min(p.length() - 1, i + 10); q++) nearDeck |= p.kind[q] != EdgePlan.GROUND;
                if (nearDeck) continue;
                int a = Math.max(0, i - 2), b = Math.min(p.length() - 1, i + 2);
                float tx = p.x[b] - p.x[a], tz = p.z[b] - p.z[a], len = (float) Math.hypot(tx, tz);
                if (len < 1e-3f) continue;
                float nx = -tz / len, nz = tx / len;
                int roadY = Math.round(p.y[i]);
                int cx = (int) Math.floor(p.x[i]), cz = (int) Math.floor(p.z[i]);
                int centreCut = natural.applyAsInt(cx, cz) - roadY;
                cutHist.merge(Math.clamp(centreCut, -2, 16), 1, Integer::sum);
                int worstFace = Integer.MIN_VALUE, wb = 0, wn = 0, wsign = 1;
                String wside = "";
                for (int side = -1; side <= 1; side += 2) {
                    int prevB = roadY, prevN = natural.applyAsInt(cx, cz);
                    int maxB = 0, maxN = 0;
                    for (int d = 1; d <= OUT; d++) {
                        int x = (int) Math.floor(p.x[i] + nx * side * d), z = (int) Math.floor(p.z[i] + nz * side * d);
                        int nat = natural.applyAsInt(x, z);
                        int built = top(level, pos, x, z, nat);
                        if (writing) written.put(io.github.profetgit.countryroads.world.StreetBands.pack(x, z), built);
                        if (d > p.hw[i]) {
                            maxB = Math.max(maxB, built - prevB);
                            maxN = Math.max(maxN, nat - prevN);
                        }
                        prevB = built;
                        prevN = nat;
                    }
                    int face = maxB - Math.max(0, maxN);
                    if (face > worstFace) {
                        worstFace = face;
                        wb = maxB;
                        wn = maxN;
                        wside = side < 0 ? "left" : "right";
                        wsign = side;
                    }
                }
                faceHist.merge(Math.clamp(worstFace, 0, 16), 1, Integer::sum);
                sites.add(new Site(cx, roadY, cz, worstFace, centreCut, wb, wn, wside, nx * wsign, nz * wsign));
            }
            if (cursor[0] < todo.size()) return false;
            if (writing) {
                for (int q = 0; q < todo.size(); q++) {
                    EdgePlan p = plans.get(todo.get(q)[0]);
                    int i = todo.get(q)[1];
                    written.putIfAbsent(io.github.profetgit.countryroads.world.StreetBands.pack((int) Math.floor(p.x[i]), (int) Math.floor(p.z[i])),
                        top(level, pos, (int) Math.floor(p.x[i]), (int) Math.floor(p.z[i]), n.terrain.sample((int) Math.floor(p.x[i]), (int) Math.floor(p.z[i])).ground()));
                }
                saveRef(written);
                report(src, "CR-CUTREF wrote " + written.size() + " columns to " + REF);
            }
            sites.sort((s1, s2) -> s2.face != s1.face ? Integer.compare(s2.face, s1.face) : Integer.compare(s2.centreCut, s1.centreCut));
            int over = 0, maxCut = Integer.MIN_VALUE;
            for (Site s : sites) {
                if (s.face > FACE_LIMIT) over++;
                maxCut = Math.max(maxCut, s.centreCut);
            }
            int shown = 0;
            List<Site> spaced = new ArrayList<>();
            for (Site s : sites) {
                boolean near = false;
                for (Site o : spaced) near |= Math.abs(o.x - s.x) + Math.abs(o.z - s.z) < 24;
                if (near) continue;
                spaced.add(s);
                report(src, "CR-CUTSITE " + s.x + " " + s.y + " " + s.z + " face=" + s.face + " (built " + s.faceBuilt + ", natural " + s.faceNatural + ", " + s.side + ") centreCut=" + s.centreCut);
                if (shown < SHOTS) {
                    // a camera across the road from the face, a little above it, looking at it
                    float camX = s.x + 0.5f - s.nx * 16, camZ = s.z + 0.5f - s.nz * 16;
                    float lookX = s.x + 0.5f + s.nx * 6 - camX, lookZ = s.z + 0.5f + s.nz * 6 - camZ;
                    float yaw = (float) Math.toDegrees(Math.atan2(-lookX, lookZ));
                    CountryRoads.LOG.info("CR-SHOT " + camX + " " + (s.y + 7) + " " + camZ + " " + yaw + " 12.0 cut" + shown + "_" + s.x + "_" + s.z);
                }
                if (++shown >= WORST) break;
            }
            List<Site> byCut = new ArrayList<>(sites);
            byCut.sort((s1, s2) -> Integer.compare(s2.centreCut, s1.centreCut));
            if (!byCut.isEmpty()) {
                Site s = byCut.getFirst();
                report(src, "CR-CUTDEEP " + s.x + " " + s.y + " " + s.z + " centreCut=" + s.centreCut);
            }
            report(src, "CR-CUT ref=" + (ref.isEmpty() ? "noise" : "unpainted") + " samples=" + sites.size() + " facesOver" + FACE_LIMIT + "=" + over + " maxFace=" + (sites.isEmpty() ? 0 : sites.getFirst().face)
                + " maxCentreCut=" + (sites.isEmpty() ? 0 : maxCut) + " faceHist=" + faceHist + " cutHist=" + cutHist);
            return true;
        });
        return 1;
    }

    /** Highest terrain block (no trees) of the built column, searched around the natural ground. */
    private static int top(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, int nat) {
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        for (int y = nat + 24; y >= nat - 40; y--) {
            var st = chunk.getBlockState(pos.set(x, y, z));
            // bridges, walls and houses are not the land (a stone bridge's parapet read as a 15-block face)
            if (PaintRules.terrain(st) && !io.github.profetgit.countryroads.retro.Retrofit.isCrafted(st)) return y;
        }
        return nat - 40;
    }
}
