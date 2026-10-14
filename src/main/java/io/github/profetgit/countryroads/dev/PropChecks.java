package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.paint.Palette;
import io.github.profetgit.countryroads.paint.PropPainter;
import io.github.profetgit.countryroads.paint.Props;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.Edge;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.phys.AABB;

/** CR-PROP* checks: planned roadside props vs what the world has, and the wildflower verges beside the roads. */
final class PropChecks {
    private PropChecks() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    static int props(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-PROPS FAIL no road network in this level");
            return 0;
        }
        BlockPos at = BlockPos.containing(src.getPosition());
        Set<Edge> edges = new LinkedHashSet<>();
        for (int rx = (at.getX() - radius) >> 9; rx <= (at.getX() + radius) >> 9; rx++)
            for (int rz = (at.getZ() - radius) >> 9; rz <= (at.getZ() + radius) >> 9; rz++) edges.addAll(n.edgesInRegion(rx, rz));
        List<CompletableFuture<EdgePlan>> futures = new ArrayList<>();
        for (Edge e : edges) futures.add(n.plan(e));
        List<EdgePlan.Decor> todo = new ArrayList<>();
        List<EdgePlan> plans = new ArrayList<>();
        int[] next = {0};
        boolean[] gathered = {false};
        int[] planned = new int[Props.NAMES.length], placed = new int[Props.NAMES.length];
        int[] cushions = {0, 0}, loot = {0, 0};
        List<Object[]> pendingBenches = new ArrayList<>();
        int[] settle = {0};
        DevCommands.queue(() -> {
            if (!gathered[0]) {
                for (var f : futures) if (!f.isDone()) return false;
                for (var f : futures) {
                    EdgePlan p = f.join();
                    if (p != null) plans.add(p);
                }
                for (EdgePlan.Decor d : ChunkMask.resolveDecor(plans)) {
                    if (d.type() != EdgePlan.PROP || Math.abs(d.x() - at.getX()) > radius || Math.abs(d.z() - at.getZ()) > radius) continue;
                    boolean inVillage = false;
                    for (EdgePlan p : plans)
                        if (p.decor.contains(d))
                            inVillage |= n.oracle.footprint(p.edge.a()).contains(d.x(), d.z(), 8) || n.oracle.footprint(p.edge.b()).contains(d.x(), d.z(), 8);
                    if (!inVillage) todo.add(d);
                }
                gathered[0] = true;
                return false;
            }
            long until = System.nanoTime() + 25_000_000L;
            while (next[0] < todo.size() && System.nanoTime() < until) check(src, level, todo.get(next[0]++), planned, placed, cushions, loot, pendingBenches);
            if (next[0] < todo.size()) return false;
            if (settle[0]++ < 40) return false;
            for (Object[] b : pendingBenches) {
                int found = level.getEntitiesOfClass(Cushion.class, (AABB) b[0]).size(), want = (Integer) b[1];
                cushions[0] += Math.min(found, want);
                if (found < want) report(src, "CR-PROP bench " + b[2] + " cushions " + found + "/" + want);
                BlockPos c = (BlockPos) b[3];
                level.setChunkForced(c.getX() >> 4, c.getZ() >> 4, false);
            }
            int all = 0, ok = 0;
            for (int i = 0; i < planned.length; i++) {
                all += planned[i];
                ok += placed[i];
            }
            boolean pass = (all == 0 || ok >= 0.7 * all) && cushions[0] == cushions[1] && loot[0] == loot[1];
            StringBuilder sb = new StringBuilder("CR-PROPS " + (pass ? "PASS" : "FAIL"));
            String[] labels = {"lost_cart", "milestone", "stash", "shrine", "bench", "hay_cart", "hay_bales"};
            for (int i = 0; i < labels.length; i++) sb.append(' ').append(labels[i]).append('=').append(placed[i]).append('/').append(planned[i]);
            sb.append(" cushions=").append(cushions[0]).append('/').append(cushions[1]).append(" loot=").append(loot[0]).append('/').append(loot[1]);
            if (all == 0) sb.append(" (none planned)");
            report(src, sb.toString());
            TreeMap<String, Integer> rej = new TreeMap<>();
            PropPainter.REJECTS.forEach((k, v) -> rej.put(k, v.get()));
            report(src, "CR-PROPREJECT laid=" + PropPainter.LAID.get() + " " + rej);
            wildflowers(src, level, plans, at, radius);
            return true;
        });
        return 1;
    }

    private static void check(CommandSourceStack src, ServerLevel level, EdgePlan.Decor d, int[] planned, int[] placed, int[] cushions, int[] loot, List<Object[]> pendingBenches) {
        Props.Prop pr = Props.byName(d.front1());
        planned[pr.id]++;
        ChunkAccess chunk = level.getChunkSource().getChunk(d.x() >> 4, d.z() >> 4, ChunkStatus.FULL, true);
        Rotation rot = Props.rotation(d.toRoad());
        int wood = Palette.wood(level.getBiome(new BlockPos(d.x(), (int) d.y(), d.z())));
        BlockState[] st = pr.states(wood);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<Props.Cell> marks = new ArrayList<>();
        for (Props.Cell c : pr.cells) {
            BlockState s = st[c.pal()];
            if (c.y() < 0 || s.isAir() || Props.isPlant(s) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.ROOTED_DIRT)) continue;
            marks.add(c);
        }
        int bestFloor = (int) d.y(), best = -1;
        for (int floor = (int) d.y() - 8; floor <= (int) d.y() + 8; floor++) {
            int hit = 0;
            for (Props.Cell c : marks) {
                BlockState want = st[c.pal()].rotate(rot);
                BlockState have = chunk.getBlockState(pos.set(d.x() + Props.rx(rot, c.x(), c.z()), floor + c.y(), d.z() + Props.rz(rot, c.x(), c.z())));
                if (have.is(want.getBlock())) hit++;
            }
            if (hit > best) {
                best = hit;
                bestFloor = floor;
            }
        }
        boolean ok = !marks.isEmpty() && best >= Math.max(1, Math.ceil(0.6 * marks.size()));
        String where = d.x() + " " + bestFloor + " " + d.z();
        if (!ok) {
            String why = PropPainter.outcome(d.x(), d.z());
            report(src, "CR-PROP " + pr.name + " " + d.x() + " " + (int) d.y() + " " + d.z() + " MISSING[" + (why == null ? "unpainted" : why) + "]");
            return;
        }
        placed[pr.id]++;
        report(src, "CR-PROP " + pr.name + " " + where + " placed");
        for (Props.Loot l : pr.loot) {
            loot[1]++;
            // a buried container follows its own column's ground, which may sit a step or two below the floor
            int lx = d.x() + Props.rx(rot, l.x(), l.z()), lz = d.z() + Props.rz(rot, l.x(), l.z());
            BlockEntity be = null;
            int ly = bestFloor + l.y();
            for (int y = ly; y >= ly - 2 && !(be instanceof RandomizableContainer); y--) {
                be = chunk.getBlockEntity(new BlockPos(lx, y, lz));
                if (be instanceof RandomizableContainer) ly = y;
            }
            boolean buried = l.y() >= 0 || !chunk.getBlockState(new BlockPos(lx, ly + 1, lz)).isAir();
            if (be instanceof RandomizableContainer rc && rc.getLootTable() != null && buried) loot[0]++;
            else report(src, "CR-PROP " + pr.name + " " + where + (buried ? " container without a loot table" : " stash open to the sky at y=" + ly));
        }
        if (pr.id == Props.BENCH) {
            int x0 = d.x() + Props.rx(rot, pr.minX, pr.minZ), x1 = d.x() + Props.rx(rot, pr.maxX, pr.maxZ);
            int z0 = d.z() + Props.rz(rot, pr.minX, pr.minZ), z1 = d.z() + Props.rz(rot, pr.maxX, pr.maxZ);
            AABB box = new AABB(Math.min(x0, x1), bestFloor - 1, Math.min(z0, z1), Math.max(x0, x1) + 1, bestFloor + 3, Math.max(z0, z1) + 1);
            cushions[1] += pr.entities.length;
            level.setChunkForced(d.x() >> 4, d.z() >> 4, true);
            pendingBenches.add(new Object[]{box, pr.entities.length, where, new BlockPos(d.x(), 0, d.z())});
        }
        // a camera 7 blocks in front of the prop (the way its local +z points) at eye height, looking at its middle
        double mx = d.x() + 0.5 + (Props.rx(rot, pr.aboveMinX, pr.aboveMinZ) + Props.rx(rot, pr.aboveMaxX, pr.aboveMaxZ)) / 2.0;
        double mz = d.z() + 0.5 + (Props.rz(rot, pr.aboveMinX, pr.aboveMinZ) + Props.rz(rot, pr.aboveMaxX, pr.aboveMaxZ)) / 2.0;
        double cx = mx + d.toRoad().getStepX() * 7, cz = mz + d.toRoad().getStepZ() * 7, cy = bestFloor + 1.62, ty = bestFloor + 1.0;
        double dx = mx - cx, dz = mz - cz, dy = ty - cy;
        report(src, String.format(java.util.Locale.ROOT, "CR-PROPSHOT %.1f %.1f %.1f %.1f %.1f %s", cx, cy, cz,
            Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))), pr.name));
    }

    /** Plant and flower columns on the three rows beside the roads near the position, by biome. */
    private static void wildflowers(CommandSourceStack src, ServerLevel level, List<EdgePlan> plans, BlockPos at, int radius) {
        List<int[]> spots = new ArrayList<>();
        for (EdgePlan p : plans)
            for (int i = 6; i < p.length() - 6; i += 9)
                if (p.kind[i] == EdgePlan.GROUND && Math.abs(p.x[i] - at.getX()) <= radius && Math.abs(p.z[i] - at.getZ()) <= radius) spots.add(new int[]{plans.indexOf(p), i});
        int step = Math.max(1, spots.size() / 150);
        List<int[]> use = new ArrayList<>();
        for (int k = 0; k < spots.size(); k += step) use.add(spots.get(k));
        TreeMap<String, long[]> byBiome = new TreeMap<>();
        int[] next = {0};
        DevCommands.queue(() -> {
            long until = System.nanoTime() + 25_000_000L;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (next[0] < use.size() && System.nanoTime() < until) {
                int[] s = use.get(next[0]++);
                EdgePlan p = plans.get(s[0]);
                int i = s[1];
                float tx = p.x[i + 3] - p.x[i - 3], tz = p.z[i + 3] - p.z[i - 3], l = (float) Math.hypot(tx, tz);
                if (l < 1e-3f) continue;
                tx /= l;
                tz /= l;
                for (int side = -1; side <= 1; side += 2)
                    for (int row = 0; row < 3; row++) {
                        double off = p.hw[i] + 0.9 + row;
                        int x = (int) Math.floor(p.x[i] - tz * side * off), z = (int) Math.floor(p.z[i] + tx * side * off);
                        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                        int g = Integer.MIN_VALUE;
                        for (int y = (int) p.y[i] + 6; y >= (int) p.y[i] - 6; y--)
                            if (PaintRules.terrain(chunk.getBlockState(pos.set(x, y, z)))) {
                                g = y;
                                break;
                            }
                        if (g == Integer.MIN_VALUE) continue;
                        BlockState above = chunk.getBlockState(pos.set(x, g + 1, z));
                        String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
                        long[] c = byBiome.computeIfAbsent(biome, k -> new long[3]);
                        c[0]++;
                        boolean plant = above.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock;
                        if (plant) c[1]++;
                        if (plant && (above.is(BlockTags.FLOWERS) || above.is(Blocks.WILDFLOWERS) || above.is(Blocks.PINK_PETALS) || above.is(Blocks.LEAF_LITTER)
                            || above.is(Blocks.SWEET_BERRY_BUSH) || above.is(Blocks.BUSH))) c[2]++;
                    }
            }
            if (next[0] < use.size()) return false;
            StringBuilder sb = new StringBuilder("CR-WILDFLOWERS (verge columns / plants / flowers within 3.6 blocks of the edge)");
            for (Map.Entry<String, long[]> e : byBiome.entrySet())
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]).append('/').append(e.getValue()[2]);
            report(src, sb.toString());
            return true;
        });
    }
}
