package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.Edge;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.Field;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** CR-FIELD* checks: planned wheat fields vs what the world has. */
final class FieldChecks {
    private FieldChecks() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static final class Stats {
        final Field f;
        final List<int[]> chunks = new ArrayList<>();
        int next;
        int farmland, wheat, water, lily, fence, hedge, missingCrop, onRoad, fenceNearRoad, squares;
        boolean scarecrow;
        final Set<Long> fences = new HashSet<>();
        final List<float[]> road = new ArrayList<>();

        Stats(Field f, List<EdgePlan> plans) {
            this.f = f;
            int x0 = (int) Math.floor(f.fx - Field.REACH) >> 4, x1 = (int) Math.ceil(f.fx + Field.REACH) >> 4;
            int z0 = (int) Math.floor(f.fz - Field.REACH) >> 4, z1 = (int) Math.ceil(f.fz + Field.REACH) >> 4;
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) if (f.nearChunk(cx, cz)) chunks.add(new int[]{cx, cz});
            for (EdgePlan p : plans)
                for (int i = 0; i < p.length(); i++)
                    if (Math.abs(p.x[i] - f.fx) < Field.REACH + 12 && Math.abs(p.z[i] - f.fz) < Field.REACH + 12 && p.kind[i] == EdgePlan.GROUND)
                        road.add(new float[]{p.x[i], p.z[i], p.hw[i]});
        }

        boolean nearRoad(int x, int z, double extra) {
            for (float[] r : road) {
                double dx = r[0] - (x + 0.5), dz = r[1] - (z + 0.5), lim = r[2] + extra;
                if (dx * dx + dz * dz < lim * lim) return true;
            }
            return false;
        }

        void scan(ServerLevel level, int cx, int cz) {
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int lx = 0; lx < 16; lx++)
                for (int lz = 0; lz < 16; lz++) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    int state = f.state(x, z);
                    boolean fenceCell = f.fenceAt(x, z), hedgeCell = f.hedgeAt(x, z) > 0;
                    boolean isScare = f.hasScarecrow && x == f.sx && z == f.sz;
                    if (state == 0 && !fenceCell && !hedgeCell && !isScare) continue;
                    for (int y = f.y + 12; y >= f.y - 16; y--) {
                        BlockState s = chunk.getBlockState(pos.set(x, y, z));
                        if (s.isAir()) continue;
                        if (s.is(Blocks.FARMLAND) && state != 0) {
                            farmland++;
                            if (!chunk.getBlockState(pos.set(x, y + 1, z)).is(Blocks.WHEAT)) missingCrop++;
                            if (nearRoad(x, z, 0)) onRoad++;
                        } else if (s.is(Blocks.WHEAT)) wheat++;
                        else if (s.is(Blocks.WATER) && state == 2) {
                            int wet = 0;
                            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                                if (level.getBlockState(new BlockPos(x + d[0], y, z + d[1])).is(Blocks.FARMLAND)) wet++;
                            if (wet >= 3) water++;
                        }
                        else if (s.is(Blocks.LILY_PAD) && state == 2) lily++;
                        else if (fenceCell && s.is(BlockTags.FENCES)) {
                            fence++;
                            fences.add(BlockPos.asLong(x, 0, z));
                            if (nearRoad(x, z, 6)) fenceNearRoad++;
                        } else if (hedgeCell && s.is(BlockTags.LEAVES) && s.getValue(LeavesBlock.PERSISTENT)) hedge++;
                        else if (isScare && s.is(Blocks.HAY_BLOCK) && chunk.getBlockState(pos.set(x, y + 1, z)).is(Blocks.CARVED_PUMPKIN)) scarecrow = true;
                    }
                }
        }

        void finish() {
            for (long k : fences) {
                int x = BlockPos.getX(k), z = BlockPos.getZ(k);
                if (fences.contains(BlockPos.asLong(x + 1, 0, z)) && fences.contains(BlockPos.asLong(x, 0, z + 1)) && fences.contains(BlockPos.asLong(x + 1, 0, z + 1))) squares++;
            }
        }

        boolean placed() {
            return farmland >= 100 && wheat >= 100;
        }

        boolean clean() {
            return squares == 0 && onRoad == 0 && fenceNearRoad == 0 && missingCrop == 0;
        }
    }

    static int fields(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-FIELDS FAIL no road network in this level");
            return 0;
        }
        BlockPos at = BlockPos.containing(src.getPosition());
        Set<Edge> edges = new LinkedHashSet<>();
        int pad = (int) Field.REACH + 128;
        for (int rx = (at.getX() - radius - pad) >> 9; rx <= (at.getX() + radius + pad) >> 9; rx++)
            for (int rz = (at.getZ() - radius - pad) >> 9; rz <= (at.getZ() + radius + pad) >> 9; rz++) edges.addAll(n.edgesInRegion(rx, rz));
        List<CompletableFuture<EdgePlan>> futures = new ArrayList<>();
        for (Edge e : edges) futures.add(n.plan(e));
        List<EdgePlan> plans = new ArrayList<>();
        List<Stats> todo = new ArrayList<>();
        boolean[] gathered = {false};
        int[] idx = {0};
        DevCommands.queue(() -> {
            if (!gathered[0]) {
                for (var f : futures) if (!f.isDone()) return false;
                for (var f : futures) {
                    EdgePlan p = f.join();
                    if (p != null) plans.add(p);
                }
                for (Field f : ChunkMask.resolveFields(plans))
                    if (Math.abs(f.fx - at.getX()) <= radius && Math.abs(f.fz - at.getZ()) <= radius) todo.add(new Stats(f, plans));
                gathered[0] = true;
                return false;
            }
            long until = System.nanoTime() + 25_000_000L;
            while (idx[0] < todo.size() && System.nanoTime() < until) {
                Stats s = todo.get(idx[0]);
                while (s.next < s.chunks.size() && System.nanoTime() < until) {
                    int[] c = s.chunks.get(s.next++);
                    s.scan(level, c[0], c[1]);
                }
                if (s.next < s.chunks.size()) return false;
                s.finish();
                line(src, level, s);
                idx[0]++;
            }
            if (idx[0] < todo.size()) return false;
            int placed = 0;
            boolean clean = true;
            StringBuilder sb = new StringBuilder();
            for (Stats s : todo) {
                if (s.placed()) placed++;
                clean &= !s.placed() || s.clean();
                sb.append(' ').append(s.f.cx()).append(',').append(s.f.cz()).append('=').append(s.placed() ? "ok" : "missing");
            }
            boolean pass = clean && (todo.isEmpty() || placed >= 0.7 * todo.size());
            report(src, "CR-FIELDS " + (pass ? "PASS" : "FAIL") + " fields=" + placed + "/" + todo.size() + (todo.isEmpty() ? " (none planned)" : "") + sb);
            return true;
        });
        return 1;
    }

    private static void line(CommandSourceStack src, ServerLevel level, Stats s) {
        Field f = s.f;
        String head = "CR-FIELD " + f.cx() + " " + f.cz();
        if (!s.placed()) {
            report(src, head + " MISSING[" + (s.farmland == 0 ? "unpainted" : "sparse") + "] farmland=" + s.farmland + " wheat=" + s.wheat);
            return;
        }
        report(src, head + " placed farmland=" + s.farmland + " wheat=" + s.wheat + " pockets=" + s.water + " lily=" + s.lily + " fence=" + s.fence + " hedge=" + s.hedge
            + " scarecrow=" + (s.scarecrow ? "yes" : f.hasScarecrow ? "MISSING" : "no(none planned)") + " missingCrop=" + s.missingCrop + " onRoad=" + s.onRoad
            + " fenceSquares=" + s.squares + " fenceNearRoad=" + s.fenceNearRoad);
        double cy = surface(level, (int) Math.floor(f.fx), (int) Math.floor(f.fz)) + 25;
        double ox = f.fx - f.tx * 30, oz = f.fz - f.tz * 30;
        shot(src, "overview", ox, cy, oz, f.fx, cy - 25, f.fz);
        double ex = f.fx - f.tx * 14, ez = f.fz - f.tz * 14;
        double ey = surface(level, (int) Math.floor(ex), (int) Math.floor(ez)) + 0.62;
        double tx = f.hasScarecrow ? f.sx + 0.5 : f.fx - f.tz * 12, tz = f.hasScarecrow ? f.sz + 0.5 : f.fz + f.tx * 12;
        double ty = f.hasScarecrow ? surface(level, f.sx, f.sz) + 2 : ey + 0.4;
        shot(src, "eye", ex, ey + 1.0, ez, tx, ty, tz);
    }

    private static int surface(ServerLevel level, int x, int z) {
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    private static void shot(CommandSourceStack src, String name, double cx, double cy, double cz, double tx, double ty, double tz) {
        double dx = tx - cx, dz = tz - cz, dy = ty - cy;
        report(src, String.format(java.util.Locale.ROOT, "CR-FIELDSHOT %.1f %.1f %.1f %.1f %.1f %s", cx, cy, cz,
            Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))), name));
    }
}
