package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.Edge;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Dev checks for the worldgen roads: pregenerate, verify the painted surface against the plan, hash, list roads. */
final class RoadChecks {
    private RoadChecks() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int[] area(CommandSourceStack src, int radius) {
        BlockPos at = BlockPos.containing(src.getPosition());
        int r = radius >> 4;
        return new int[]{(at.getX() >> 4) - r, (at.getZ() >> 4) - r, 2 * r + 1};
    }

    /** Generates every chunk in the square to FULL, a time budget per tick. */
    static int pregen(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        int[] a = area(src, radius);
        int total = a[2] * a[2];
        int[] next = {0};
        long[] worst = {0};
        long t0 = System.nanoTime();
        RoadNetwork n = RoadNetwork.get(level);
        DevCommands.queue(() -> {
            long start = System.nanoTime(), until = start + 45_000_000L;
            while (next[0] < total && System.nanoTime() < until) {
                int i = next[0]++;
                level.getChunkSource().getChunk(a[0] + i % a[2], a[1] + i / a[2], ChunkStatus.FULL, true);
            }
            worst[0] = Math.max(worst[0], System.nanoTime() - start);
            if (next[0] < total) return false;
            report(src, String.format("CR-PREGEN chunks=%d s=%.1f worstSliceMs=%.0f routed=%d failed=%d routeMs=%d genWaitMs=%d",
                total, (System.nanoTime() - t0) / 1e9, worst[0] / 1e6, n == null ? 0 : n.routed.get(), n == null ? 0 : n.failed.get(),
                n == null ? 0 : n.routeNanos.get() / 1_000_000, n == null ? 0 : n.waitNanos.get() / 1_000_000));
            return true;
        });
        return 1;
    }

    private static boolean isSurface(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || io.github.profetgit.countryroads.paint.PaintRules.solid(s);
    }

    /** Every painted column must carry the planned surface; adjacent road columns must not jump more than a block. */
    static int check(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-CHECK FAIL no road network in this level");
            return 0;
        }
        int[] a = area(src, radius);
        int total = a[2] * a[2];
        int[] next = {0};
        long[] cols = {0}, bad = {0}, breaks = {0}, steps = {0}, skipped = {0}, floating = {0}, snowyBare = {0};
        Set<String> examples = new LinkedHashSet<>();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        DevCommands.queue(() -> {
            long until = System.nanoTime() + 45_000_000L;
            while (next[0] < total && System.nanoTime() < until) {
                int i = next[0]++;
                int cx = a[0] + i % a[2], cz = a[1] + i / a[2];
                ChunkMask m = n.mask(cx, cz);
                if (!m.any()) continue;
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
                var saved = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) chunk).getAttached(io.github.profetgit.countryroads.world.RoadColumns.TYPE);
                boolean hasRoad = false;
                for (float v : m.in) hasRoad |= v >= 0;
                if (saved != null && !saved.any() && hasRoad) {
                    // deliberately left alone (old-world chunk players live in)
                    skipped[0]++;
                    continue;
                }
                for (int c = 0; c < 256; c++) {
                    if (m.in[c] < -5 || m.in[c] == Float.NEGATIVE_INFINITY) continue;
                    int fx = (cx << 4) + (c & 15), fz = (cz << 4) + (c >> 4), ft = (int) Math.floor(m.surface(c));
                    for (int y = ft - 3; y <= ft + 14; y++) {
                        BlockState st = chunk.getBlockState(pos.set(fx, y, fz));
                        if (st.isAir()) continue;
                        BlockState below = chunk.getBlockState(pos.set(fx, y - 1, fz));
                        boolean plant = st.canBeReplaced() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW) && !st.is(BlockTags.LEAVES) && !st.is(Blocks.VINE) && !st.is(BlockTags.CAVE_VINES) && !st.is(Blocks.HANGING_ROOTS);
                        if (plant && below.isAir()) {
                            floating[0]++;
                            if (examples.size() < 6) examples.add("floating " + fx + "," + y + "," + fz + "=" + st.getBlock().getDescriptionId());
                        }
                        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
                            && st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
                            BlockState up = chunk.getBlockState(pos.set(fx, y + 1, fz));
                            if (up.isAir()) {
                                snowyBare[0]++;
                                if (examples.size() < 6) examples.add("frosty " + fx + "," + y + "," + fz);
                            }
                        }
                    }
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int c = ChunkMask.idx(lx, lz);
                        if (m.in[c] < 0) continue;
                        double q = m.surface(c);
                        int top = (int) Math.floor(q);
                        boolean half = q - top >= 0.5;
                        int x = (cx << 4) + lx, z = (cz << 4) + lz;
                        cols[0]++;
                        BlockState surf = chunk.getBlockState(pos.set(x, top, z));
                        BlockState above = chunk.getBlockState(pos.set(x, top + 1, z));
                        boolean ok = isSurface(surf) && (above.getBlock() instanceof SlabBlock || above.isAir())
                            && chunk.getBlockState(pos.set(x, top + 2, z)).isAir();
                        if (!ok) {
                            bad[0]++;
                            if (examples.size() < 6) examples.add(x + "," + top + "," + z + "=" + surf.getBlock().getDescriptionId() + "/" + above.getBlock().getDescriptionId());
                        }
                        for (int k = top - 1; k <= top + 3; k++)
                            sha.update(chunk.getBlockState(pos.set(x, k, z)).toString().getBytes());
                        if (lx < 15) breaks(m, c, ChunkMask.idx(lx + 1, lz), breaks, steps);
                        else {
                            ChunkMask e = n.mask(cx + 1, cz);
                            breaks(m, c, e, ChunkMask.idx(0, lz), breaks, steps);
                        }
                        if (lz < 15) breaks(m, c, ChunkMask.idx(lx, lz + 1), breaks, steps);
                        else {
                            ChunkMask s = n.mask(cx, cz + 1);
                            breaks(m, c, s, ChunkMask.idx(lx, 0), breaks, steps);
                        }
                    }
                }
            }
            if (next[0] < total) return false;
            String verdict = cols[0] > 0 && bad[0] == 0 && breaks[0] == 0 && floating[0] == 0 && snowyBare[0] == 0 ? "PASS" : "FAIL";
            report(src, "CR-CHECK " + verdict + " roadColumns=" + cols[0] + " bad=" + bad[0] + " breaks=" + breaks[0] + " oneBlockSteps=" + steps[0] + " skippedChunks=" + skipped[0] + " floatingPlants=" + floating[0] + " frostyBareGrass=" + snowyBare[0]
                + " examples=" + examples);
            report(src, "CR-HASH " + HexFormat.of().formatHex(sha.digest()).substring(0, 16) + " columns=" + cols[0]);
            return true;
        });
        return 1;
    }

    private static void breaks(ChunkMask m, int c, int c2, long[] breaks, long[] steps) {
        breaks(m, c, m, c2, breaks, steps);
    }

    private static void breaks(ChunkMask m, int c, ChunkMask m2, int c2, long[] breaks, long[] steps) {
        if (m2.in[c2] < 0) return;
        double d = Math.abs(m.surface(c) - m2.surface(c2));
        if (d > 1) breaks[0]++;
        else if (d == 1) steps[0]++;
    }

    /**
     * Camera positions along roads in the area: the steepest stretch, a causeway if any, the curviest and a plain
     * one, each from the roadside, plus an overhead view. Lines: CR-SHOT x y z yaw pitch label.
     */
    static int shots(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        List<EdgePlan> plans = new ArrayList<>();
        for (EdgePlan p : n.readyPlans()) {
            for (int i = 0; i < p.length(); i += 8)
                if (Math.abs(p.x[i] - at.getX()) <= radius && Math.abs(p.z[i] - at.getZ()) <= radius) {
                    plans.add(p);
                    break;
                }
        }
        if (plans.isEmpty()) {
            report(src, "CR-SHOT none");
            return 0;
        }
        // best sample per label: steep, curve, plain, and the middle of the longest run of each structure kind
        String[] labels = {"steep", "curve", "plain", "bridge", "boardwalk", "tunnel"};
        double[] best = new double[labels.length];
        java.util.Arrays.fill(best, -1);
        int[][] pick = new int[labels.length][];
        for (int pi = 0; pi < plans.size(); pi++) {
            EdgePlan p = plans.get(pi);
            for (int i = 20; i + 20 < p.length(); i += 3) {
                if (Math.abs(p.x[i] - at.getX()) > radius || Math.abs(p.z[i] - at.getZ()) > radius) continue;
                double slope = Math.abs(p.y[i + 10] - p.y[i - 10]);
                double turn = Math.abs(Math.atan2(p.z[i + 15] - p.z[i], p.x[i + 15] - p.x[i]) - Math.atan2(p.z[i] - p.z[i - 15], p.x[i] - p.x[i - 15]));
                if (turn > Math.PI) turn = 2 * Math.PI - turn;
                boolean ground = p.kind[i] == EdgePlan.GROUND;
                double run = p.runPos[i] + p.runLen[i] * 0.01;
                double[] score = {ground ? slope : -1, ground ? turn : -1, ground ? 1 / (1 + slope + turn) : -1,
                    p.kind[i] == EdgePlan.BRIDGE ? run : -1, p.kind[i] == EdgePlan.BOARDWALK ? run : -1, p.kind[i] == EdgePlan.TUNNEL ? run : -1};
                for (int k = 0; k < labels.length; k++)
                    if (score[k] > best[k]) {
                        best[k] = score[k];
                        pick[k] = new int[]{pi, i};
                    }
            }
        }
        for (int k = 0; k < labels.length; k++) {
            if (pick[k] == null || best[k] < 0) continue;
            EdgePlan p = plans.get(pick[k][0]);
            int i = pick[k][1];
            float tx = p.x[i + 6] - p.x[i - 6], tz = p.z[i + 6] - p.z[i - 6];
            float len = (float) Math.hypot(tx, tz);
            tx /= len;
            tz /= len;
            double back = k == 5 ? 22 : 13, side = k >= 3 ? 14 : 7, up = k == 5 ? 3 : k >= 3 ? 6 : 5;
            double cx = p.x[i] - tx * back - tz * side, cz = p.z[i] - tz * back + tx * side, cy = p.y[i] + up;
            if (k == 5) {
                // look into the tunnel mouth from outside, straight along the road
                int j = Math.max(0, i - p.runPos[i] - 1);
                cx = p.x[j] - tx * 12;
                cz = p.z[j] - tz * 12;
                cy = p.y[j] + 3;
                shot(src, cx, cy, cz, p.x[j], p.y[j] + 2, p.z[j], labels[k]);
                continue;
            }
            shot(src, cx, cy, cz, p.x[i] + tx * 4, p.y[i], p.z[i] + tz * 4, labels[k]);
            if (k == 2) {
                EdgePlan.Decor sign = null, lamp = null;
                EdgePlan camp = null;
                for (EdgePlan q : plans) {
                    for (EdgePlan.Decor d : q.decor) {
                        if (Math.abs(d.x() - at.getX()) > radius || Math.abs(d.z() - at.getZ()) > radius) continue;
                        if (d.type() == EdgePlan.SIGN && sign == null) sign = d;
                        if (d.type() == EdgePlan.LAMP && lamp == null) lamp = d;
                    }
                    if (q.campChunk != 0 && camp == null) camp = q;
                }
                if (sign != null) {
                    double fx = sign.toRoad().getStepX(), fz = sign.toRoad().getStepZ(), ax = sign.along().getStepX(), az = sign.along().getStepZ();
                    shot(src, sign.x() + 0.5 + fx * 5 - ax * 2.5, sign.y() + 3.2, sign.z() + 0.5 + fz * 5 - az * 2.5, sign.x() + 0.5, sign.y() + 3, sign.z() + 0.5, "sign");
                }
                EdgePlan.Decor junction = null;
                for (EdgePlan q : plans)
                    for (EdgePlan.Decor d : q.decor)
                        if (junction == null && d.back().contains("\n") && Math.abs(d.x() - at.getX()) <= radius && Math.abs(d.z() - at.getZ()) <= radius) junction = d;
                if (junction != null)
                    shot(src, junction.x() - 14.5, junction.y() + 22, junction.z() - 14.5, junction.x() + 4, junction.y(), junction.z() + 4, "junction");
                for (EdgePlan q : plans) {
                    if (Math.abs(q.x[0] - at.getX()) > radius || Math.abs(q.z[0] - at.getZ()) > radius || q.length() < 40) continue;
                    float ux = q.x[0] - q.x[30], uz = q.z[0] - q.z[30];
                    float ul = (float) Math.hypot(ux, uz);
                    shot(src, q.x[30] - ux / ul * 6 + 3, q.y[30] + 10, q.z[30] - uz / ul * 6 + 3, q.x[0], q.y[0] + 1, q.z[0], "villagejoin");
                    break;
                }
                if (lamp != null) {
                    double fx = lamp.toRoad().getStepX(), fz = lamp.toRoad().getStepZ(), ax = lamp.along().getStepX(), az = lamp.along().getStepZ();
                    shot(src, lamp.x() + 0.5 + fx * 6 + ax * 4, lamp.y() + 3.5, lamp.z() + 0.5 + fz * 6 + az * 4, lamp.x() + 0.5, lamp.y() + 3, lamp.z() + 0.5, "lamp");
                }
                for (byte type = 0; type <= 1; type++) {
                    for (EdgePlan q : plans) {
                        EdgePlan.Run hit = null;
                        for (EdgePlan.Run r : q.runs) {
                            int mid = (r.s0() + r.s1()) / 2;
                            if (r.type() == type && Math.abs(q.x[mid] - at.getX()) <= radius && Math.abs(q.z[mid] - at.getZ()) <= radius) hit = r;
                        }
                        if (hit == null) continue;
                        int mid = (hit.s0() + hit.s1()) / 2, b = Math.max(0, hit.s0() - 4);
                        double ux = q.x[mid] - q.x[b], uz = q.z[mid] - q.z[b], ul = Math.hypot(ux, uz);
                        int sd = hit.side() == 0 ? 1 : hit.side();
                        double rx = -uz / ul * sd, rz = ux / ul * sd, off = q.hw[mid] + 1.5;
                        // from the far edge of the road, behind the run, looking along the wall line
                        shot(src, q.x[b] - rx * 1.5, q.y[b] + 2.6, q.z[b] - rz * 1.5, q.x[mid] + rx * off, q.y[mid] + 0.6, q.z[mid] + rz * off,
                            type == 0 ? "stonewall" : "fence");
                        break;
                    }
                }
                if (camp != null && !camp.campBoxes.isEmpty()) {
                    // from the road's nearest point, above the road, looking at the camp
                    var b = camp.campBoxes.getFirst();
                    double mx = (b.minX() + b.maxX()) / 2.0, mz = (b.minZ() + b.maxZ()) / 2.0, my = n.terrain.sample((int) mx, (int) mz).ground();
                    int best2 = 0;
                    for (int j = 0; j < camp.length(); j++)
                        if (Math.hypot(camp.x[j] - mx, camp.z[j] - mz) < Math.hypot(camp.x[best2] - mx, camp.z[best2] - mz)) best2 = j;
                    double rx = camp.x[best2] - (mx - camp.x[best2]) * 0.3, rz = camp.z[best2] - (mz - camp.z[best2]) * 0.3;
                    shot(src, rx, Math.max(camp.y[best2], my) + 7, rz, mx, my + 1, mz, "camp");
                }
            }
            if (k == 0) shot(src, p.x[i] - tx * 20, p.y[i] + 36, p.z[i] - tz * 20, p.x[i] + tx * 10, p.y[i], p.z[i] + tz * 10, "overhead");
        }
        return 1;
    }

    private static void shot(CommandSourceStack src, double x, double y, double z, double lx, double ly, double lz, String label) {
        double dx = lx - x, dy = ly - y, dz = lz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        report(src, String.format(java.util.Locale.ROOT, "CR-SHOT %.1f %.1f %.1f %.1f %.1f %s", x, y, z, yaw, pitch, label));
    }

    /** Planned signs, lamps and camps in the area vs what the world has. */
    static int decor(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        DevCommands.queue(() -> {
            int signs = 0, signsFound = 0, lamps = 0, lampsFound = 0, camps = 0, campsFound = 0;
            Set<String> names = new LinkedHashSet<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            var resolved = ChunkMask.resolveDecor(n.readyPlans());
            for (EdgePlan p : n.readyPlans()) {
                for (EdgePlan.Decor d : p.decor) {
                    if (!resolved.contains(d)) continue;
                    if (Math.abs(d.x() - at.getX()) > radius || Math.abs(d.z() - at.getZ()) > radius) continue;
                    if (n.oracle.footprint(p.edge.a()).contains(d.x(), d.z(), 1) || n.oracle.footprint(p.edge.b()).contains(d.x(), d.z(), 1)) continue;
                    ChunkAccess chunk = level.getChunkSource().getChunk(d.x() >> 4, d.z() >> 4, ChunkStatus.FULL, true);
                    boolean found = false;
                    for (int k = 0; k < 13 * 5 && !found; k++) {
                        int y = (int) d.y() - 5 + k % 13;
                        var nb = k / 13 == 0 ? net.minecraft.core.BlockPos.ZERO : net.minecraft.core.Direction.from2DDataValue(k / 13 - 1).getUnitVec3i();
                        BlockState st = chunk.getBlockState(pos.set(d.x() + nb.getX(), y, d.z() + nb.getZ()));
                        found = d.type() == EdgePlan.SIGN ? st.is(BlockTags.ALL_SIGNS) : st.is(Blocks.LANTERN);
                    }
                    if (d.type() == EdgePlan.SIGN) {
                        signs++;
                        if (found) signsFound++;
                        else report(src, "CR-MISSING sign at " + d.x() + "," + (int) d.y() + "," + d.z());
                        names.add(d.front1());
                    } else {
                        lamps++;
                        if (found) lampsFound++;
                    }
                }
                if (p.campChunk != 0) {
                    var c = net.minecraft.world.level.ChunkPos.unpack(p.campChunk);
                    if (Math.abs((c.x() << 4) - at.getX()) > radius || Math.abs((c.z() << 4) - at.getZ()) > radius) continue;
                    camps++;
                    ChunkAccess chunk = level.getChunkSource().getChunk(c.x(), c.z(), ChunkStatus.FULL, true);
                    var st = registry.get(p.camp).map(h -> chunk.getStartForStructure(h.value())).orElse(null);
                    if (st != null && st.isValid()) campsFound++;
                    report(src, "CR-CAMP " + p.camp + " at " + (c.x() << 4) + "," + (c.z() << 4) + (st != null && st.isValid() ? " present" : " MISSING"));
                }
            }
            int runs = 0, runCols = 0, runHit = 0;
            for (EdgePlan p : n.readyPlans()) {
                for (EdgePlan.Run r : p.runs) {
                    int mid = (r.s0() + r.s1()) / 2;
                    if (Math.abs(p.x[mid] - at.getX()) > radius || Math.abs(p.z[mid] - at.getZ()) > radius) continue;
                    runs++;
                    for (int i = r.s0() + 1; i < r.s1() - 1; i += 2) {
                        float tx = p.x[i + 1] - p.x[i - 1], tz = p.z[i + 1] - p.z[i - 1], len = (float) Math.hypot(tx, tz);
                        for (int side = -1; side <= 1; side += 2) {
                            if (r.side() != 0 && r.side() != side) continue;
                            float off = p.hw[i] + 1.5f;
                            int x = (int) Math.floor(p.x[i] - tz / len * side * off), z = (int) Math.floor(p.z[i] + tx / len * side * off);
                            ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                            runCols++;
                            boolean hit = false;
                            for (int y = (int) p.y[i] - 2; y <= (int) p.y[i] + 3 && !hit; y++) {
                                BlockState st = chunk.getBlockState(pos.set(x, y, z));
                                hit = st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.is(BlockTags.SLABS) || st.is(BlockTags.STAIRS)
                                    || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.STONE);
                            }
                            if (hit) runHit++;
                        }
                    }
                }
            }
            report(src, "CR-RUNS runs=" + runs + " columnsWithWallOrFence=" + runHit + "/" + runCols);
            String verdict = signsFound == signs && campsFound == camps && lampsFound >= lamps * 0.8 ? "PASS" : "FAIL";
            report(src, "CR-DECOR " + verdict + " signs=" + signsFound + "/" + signs + " lamps=" + lampsFound + "/" + lamps + " camps=" + campsFound + "/" + camps + " names=" + names);
            return true;
        });
        return 1;
    }

    /** Saved road columns match the plan, and monsters may not spawn on them (spawner rule called directly). */
    static int perks(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        int[] a = area(src, radius);
        DevCommands.queue(() -> {
            long planned = 0, saved = 0, mismatch = 0, tested = 0, blocked = 0, offTested = 0, offAllowed = 0;
            try {
                var m = net.minecraft.world.level.NaturalSpawner.class.getDeclaredMethod("isValidSpawnPostitionForType", ServerLevel.class,
                    net.minecraft.world.entity.MobCategory.class, net.minecraft.world.level.StructureManager.class,
                    net.minecraft.world.level.chunk.ChunkGenerator.class, net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData.class,
                    BlockPos.MutableBlockPos.class, double.class);
                m.setAccessible(true);
                for (int k = 0; k < a[2] * a[2]; k++) {
                    int cx = a[0] + k % a[2], cz = a[1] + k / a[2];
                    ChunkMask mask = n.mask(cx, cz);
                    if (!mask.any()) continue;
                    var chunk = (net.minecraft.world.level.chunk.LevelChunk) level.getChunk(cx, cz);
                    var cols = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) chunk).getAttached(io.github.profetgit.countryroads.world.RoadColumns.TYPE);
                    for (int lz = 0; lz < 16; lz++)
                        for (int lx = 0; lx < 16; lx++) {
                            int c = ChunkMask.idx(lx, lz);
                            boolean plan = mask.in[c] >= 0;
                            boolean have = cols != null && cols.road(lx, lz);
                            if (plan) planned++;
                            if (have) saved++;
                            if (plan != have) mismatch++;
                            int x = (cx << 4) + lx, z = (cz << 4) + lz;
                            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                            var mobs = level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), net.minecraft.world.entity.MobCategory.MONSTER, new BlockPos(x, y, z)).unwrap();
                            var zombie = mobs.stream().map(w -> w.value()).filter(d -> d.type() == net.minecraft.world.entity.EntityTypes.ZOMBIE).findFirst().orElse(null);
                            if (zombie == null) continue;
                            if (have && cols.open(lx, lz) && (x + z) % 7 == 0) {
                                tested++;
                                if (!(boolean) m.invoke(null, level, net.minecraft.world.entity.MobCategory.MONSTER, level.structureManager(),
                                    level.getChunkSource().getGenerator(), zombie, new BlockPos.MutableBlockPos(x, y, z), 600.0)) blocked++;
                            } else if (!have && mask.in[c] < -3 && (x + z) % 7 == 0 && offTested < 400) {
                                offTested++;
                                if ((boolean) m.invoke(null, level, net.minecraft.world.entity.MobCategory.MONSTER, level.structureManager(),
                                    level.getChunkSource().getGenerator(), zombie, new BlockPos.MutableBlockPos(x, y, z), 600.0)) offAllowed++;
                            }
                        }
                }
            } catch (ReflectiveOperationException e) {
                report(src, "CR-PERKS FAIL " + e);
                return true;
            }
            String verdict = planned > 0 && mismatch == 0 && blocked == tested && offAllowed > 0 ? "PASS" : "FAIL";
            report(src, "CR-PERKS " + verdict + " plannedColumns=" + planned + " savedColumns=" + saved + " mismatch=" + mismatch
                + " monsterOnRoadBlocked=" + blocked + "/" + tested + " monsterOffRoadAllowed=" + offAllowed + "/" + offTested);
            return true;
        });
        return 1;
    }

    static int roads(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-ROADS none");
            return 0;
        }
        BlockPos at = BlockPos.containing(src.getPosition());
        Set<Edge> edges = new LinkedHashSet<>();
        for (int rx = (at.getX() - radius) >> 9; rx <= (at.getX() + radius) >> 9; rx++)
            for (int rz = (at.getZ() - radius) >> 9; rz <= (at.getZ() + radius) >> 9; rz++)
                edges.addAll(n.edgesInRegion(rx, rz));
        List<Edge> list = new ArrayList<>(edges);
        int[] next = {0}, ok = {0};
        long[] len = {0};
        DevCommands.queue(() -> {
            if (next[0] < list.size()) {
                Edge e = list.get(next[0]++);
                EdgePlan p = n.plan(e).join();
                if (p != null) {
                    ok[0]++;
                    len[0] += p.length();
                }
                String line = "CR-ROAD " + e.a().pos().toShortString() + " -> " + e.b().pos().toShortString() + " straight=" + (int) e.length()
                    + (p == null ? " NONE" : " length=" + p.length() + " ms=" + p.routeNanos / 1_000_000) + " trunks=" + n.trunkDeps(e).size();
                CountryRoads.LOG.info(line);
                return false;
            }
            report(src, "CR-ROADS edges=" + list.size() + " routed=" + ok[0] + " totalLength=" + len[0]);
            return true;
        });
        return 1;
    }
}
