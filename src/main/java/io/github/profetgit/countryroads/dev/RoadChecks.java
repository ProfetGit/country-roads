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
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.GRAVEL) || s.is(Blocks.GRASS_BLOCK);
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
        long[] cols = {0}, bad = {0}, breaks = {0}, steps = {0};
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
                        boolean ok = isSurface(surf) && (half ? above.getBlock() instanceof SlabBlock : above.isAir());
                        for (int y = top + (half ? 2 : 1); y <= top + 2 && ok; y++)
                            ok = chunk.getBlockState(pos.set(x, y, z)).isAir();
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
            String verdict = cols[0] > 0 && bad[0] == 0 && breaks[0] == 0 ? "PASS" : "FAIL";
            report(src, "CR-CHECK " + verdict + " roadColumns=" + cols[0] + " bad=" + bad[0] + " breaks=" + breaks[0] + " oneBlockSteps=" + steps[0]
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
        double[] best = {-1, -1, -1, -1};
        int[][] pick = new int[4][];
        for (int pi = 0; pi < plans.size(); pi++) {
            EdgePlan p = plans.get(pi);
            for (int i = 20; i + 20 < p.length(); i += 3) {
                if (Math.abs(p.x[i] - at.getX()) > radius || Math.abs(p.z[i] - at.getZ()) > radius) continue;
                double slope = Math.abs(p.y[i + 10] - p.y[i - 10]);
                double turn = Math.abs(Math.atan2(p.z[i + 15] - p.z[i], p.x[i + 15] - p.x[i]) - Math.atan2(p.z[i] - p.z[i - 15], p.x[i] - p.x[i - 15]));
                if (turn > Math.PI) turn = 2 * Math.PI - turn;
                double[] score = {slope, p.kind[i] == EdgePlan.CAUSEWAY ? 1 + slope : -1, turn, 1 / (1 + slope + turn)};
                for (int k = 0; k < 4; k++)
                    if (score[k] > best[k]) {
                        best[k] = score[k];
                        pick[k] = new int[]{pi, i};
                    }
            }
        }
        String[] labels = {"steep", "causeway", "curve", "plain"};
        for (int k = 0; k < 4; k++) {
            if (pick[k] == null || best[k] < 0) continue;
            EdgePlan p = plans.get(pick[k][0]);
            int i = pick[k][1];
            float tx = p.x[i + 6] - p.x[i - 6], tz = p.z[i + 6] - p.z[i - 6];
            float len = (float) Math.hypot(tx, tz);
            tx /= len;
            tz /= len;
            double cx = p.x[i] - tx * 13 - tz * 7, cz = p.z[i] - tz * 13 + tx * 7, cy = p.y[i] + 5;
            shot(src, cx, cy, cz, p.x[i] + tx * 4, p.y[i], p.z[i] + tz * 4, labels[k]);
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
                    + (p == null ? " NONE" : " length=" + p.length() + " ms=" + p.routeNanos / 1_000_000);
                CountryRoads.LOG.info(line);
                return false;
            }
            report(src, "CR-ROADS edges=" + list.size() + " routed=" + ok[0] + " totalLength=" + len[0]);
            return true;
        });
        return 1;
    }
}
