package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.StreetBands;
import io.github.profetgit.countryroads.world.VillageOracle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Village street checks: band geometry read from the templates against what the generator really placed. */
final class StreetChecks {
    private static final boolean DUMP = Boolean.getBoolean("countryroads.streetdump");

    private StreetChecks() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    /** CR-STREETS: per village in radius, band size and width, street top block and Y against the planner's ground. */
    static int streets(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        int r = radius >> 4;
        DevCommands.queue(() -> {
            List<VillageOracle.Village> found = n.oracle.find((at.getX() >> 4) - r, (at.getZ() >> 4) - r, (at.getX() >> 4) + r, (at.getZ() >> 4) + r);
            int mapped = 0;
            for (VillageOracle.Village v : found) {
                VillageOracle.Footprint fp = n.oracle.footprint(v);
                StreetBands bands = fp.bands();
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (var b : fp.pieces()) {
                    minX = Math.min(minX, b.minX());
                    minZ = Math.min(minZ, b.minZ());
                    maxX = Math.max(maxX, b.maxX());
                    maxZ = Math.max(maxZ, b.maxZ());
                }
                for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
                    for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
                Map<String, Integer> tops = new TreeMap<>();
                Map<Integer, Integer> dys = new TreeMap<>();
                List<Integer> widths = new ArrayList<>();
                int sampled = 0, outsideBox = 0;
                for (int[] c : bands.columns()) {
                    boolean inStreetBox = false;
                    for (var b : fp.streets()) inStreetBox |= c[0] >= b.minX() && c[0] <= b.maxX() && c[1] >= b.minZ() && c[1] <= b.maxZ();
                    if (!inStreetBox) outsideBox++;
                    ChunkAccess chunk = level.getChunkSource().getChunk(c[0] >> 4, c[1] >> 4, ChunkStatus.FULL, true);
                    int ground = n.terrain.sample(c[0], c[1]).ground();
                    int pathY = Integer.MIN_VALUE;
                    BlockState st = null;
                    for (int y = ground + 14; y >= ground - 14; y--) {
                        BlockState b = chunk.getBlockState(new BlockPos(c[0], y, c[1]));
                        if (b.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || b.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)
                            || b.is(net.minecraft.world.level.block.Blocks.GRAVEL) || b.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)) {
                            pathY = y;
                            st = b;
                            break;
                        }
                    }
                    if (st == null) {
                        tops.merge("none", 1, Integer::sum);
                    } else {
                        tops.merge(st.getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                        dys.merge(pathY - ground, 1, Integer::sum);
                    }
                    int rx = 1, rz = 1;
                    for (int d = 1; bands.contains(c[0] + d, c[1]); d++) rx++;
                    for (int d = 1; bands.contains(c[0] - d, c[1]); d++) rx++;
                    for (int d = 1; bands.contains(c[0], c[1] + d); d++) rz++;
                    for (int d = 1; bands.contains(c[0], c[1] - d); d++) rz++;
                    widths.add(Math.min(rx, rz));
                    sampled++;
                }
                Collections.sort(widths);
                int medianWidth = widths.isEmpty() ? 0 : widths.get(widths.size() / 2);
                report(src, "CR-STREETS " + v.structure() + " " + v.pos().toShortString() + " pieces=" + fp.pieces().size() + " streets=" + fp.streets().size()
                    + " bands=" + bands.size() + " outsideStreetBoxes=" + outsideBox + " medianWidth=" + medianWidth + " tops=" + tops + " dyVsGround=" + dys);
                if (mapped++ < 2 && sampled > 0) {
                    int w = Math.min(maxX - minX + 1, 96), h = Math.min(maxZ - minZ + 1, 96);
                    for (int z = 0; z < h; z++) {
                        StringBuilder row = new StringBuilder("CR-STREETMAP ");
                        for (int x = 0; x < w; x++) row.append(bands.contains(minX + x, minZ + z) ? '#' : '.');
                        CountryRoads.LOG.info(row.toString());
                    }
                }
            }
            report(src, "CR-STREETS done villages=" + found.size());
            return true;
        });
        return 1;
    }

    /** CR-STREETSNAP: hashes of village columns off the street bands (must not change with the feature) and on them, plus the band tops. */
    static int snap(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        int r = radius >> 4;
        DevCommands.queue(() -> {
            List<VillageOracle.Village> found = n.oracle.find((at.getX() >> 4) - r, (at.getZ() >> 4) - r, (at.getX() >> 4) + r, (at.getZ() >> 4) + r);
            for (VillageOracle.Village v : found) {
                VillageOracle.Footprint fp = n.oracle.footprint(v);
                StreetBands bands = fp.bands();
                long off = 1125899906842597L, on = 1125899906842597L;
                Map<String, Integer> tops = new TreeMap<>();
                java.util.Set<Long> seen = new java.util.HashSet<>();
                for (var b : fp.pieces()) {
                    for (int x = b.minX(); x <= b.maxX(); x++)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) {
                            if (!seen.add(StreetBands.pack(x, z))) continue;
                            ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                            boolean band = bands.contains(x, z);
                            int ground = n.terrain.sample(x, z).ground();
                            long h = 0;
                            int topPath = Integer.MIN_VALUE;
                            for (int y = ground + 30; y >= ground - 14; y--) {
                                BlockState st = chunk.getBlockState(new BlockPos(x, y, z));
                                if (y <= ground + (band ? 2 : 0) && y >= ground - 6) h = h * 31 + Block.getId(st);
                                if (band && topPath == Integer.MIN_VALUE && !st.isAir() && (st.isSolid() || st.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) {
                                    topPath = y;
                                    tops.merge(st.getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                                }
                            }
                            if (DUMP && !band) CountryRoads.LOG.info("CR-STREETCOL " + x + "," + z + " " + Long.toHexString(h));
                            if (band) on = on * 1000003 + h * 31 + x * 7 + z;
                            else off = off * 1000003 + h * 31 + x * 7 + z;
                        }
                }
                report(src, "CR-STREETSNAP " + v.pos().toShortString() + " off=" + Long.toHexString(off) + " on=" + Long.toHexString(on) + " tops=" + tops);
            }
            report(src, "CR-STREETSNAP done villages=" + found.size());
            return true;
        });
        return 1;
    }

    private static boolean roadBlock(BlockState st) {
        return st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PACKED_MUD) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.GRAVEL)
            || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.SMOOTH_SANDSTONE) || st.is(Blocks.SMOOTH_RED_SANDSTONE) || st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK)
            || st.is(Blocks.PODZOL) || st.is(Blocks.MUD_BRICKS) || st.is(Blocks.STONE) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND) || st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.SLABS) || st.is(net.minecraft.tags.BlockTags.STAIRS);
    }

    /** Highest road-ish block within y0-4..y0+4 of the column, or MIN_VALUE. */
    private static int surfaceAt(ServerLevel level, int x, int z, int y0) {
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        for (int y = y0 + 4; y >= y0 - 4; y--) {
            BlockState st = chunk.getBlockState(new BlockPos(x, y, z));
            if (!st.isAir() && (st.isSolid() || st.is(Blocks.DIRT_PATH))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Whether the point lies on a connector of another plan (roads that share a trunk leave the village over its connector). */
    private static boolean sharesConnector(RoadNetwork n, io.github.profetgit.countryroads.plan.EdgePlan self, float x, float z) {
        for (var q : n.readyPlans()) {
            if (q == self) continue;
            for (int i = 0; i < q.length(); i++) {
                boolean conn = i < q.connA || i >= q.length() - q.connB;
                if (conn && Math.abs(q.x[i] - x) <= 3 && Math.abs(q.z[i] - z) <= 3) return true;
            }
        }
        return false;
    }

    /** CR-DOCK: where each planned road meets a village street: dock on the band, same height, a walkable run of road onto it. */
    static int docks(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        BlockPos at = BlockPos.containing(src.getPosition());
        DevCommands.queue(() -> {
            int ends = 0, ok = 0, noConn = 0;
            for (var p : n.readyPlans()) {
                for (int side = 0; side < 2; side++) {
                    int conn = side == 0 ? p.connA : p.connB;
                    var village = side == 0 ? p.edge.a() : p.edge.b();
                    int i0 = side == 0 ? 0 : p.length() - 1;
                    if (Math.abs(p.x[i0] - at.getX()) > radius || Math.abs(p.z[i0] - at.getZ()) > radius) continue;
                    ends++;
                    VillageOracle.Footprint fp = n.oracle.footprint(village);
                    if (conn == 0 && sharesConnector(n, p, p.x[i0], p.z[i0])) {
                        ends--;
                        report(src, "CR-DOCK SHARED " + village.pos().toShortString() + " end=" + (side == 0 ? "A" : "B") + " rides another road's connector");
                        continue;
                    }
                    if (conn == 0) {
                        noConn++;
                        report(src, "CR-DOCK " + village.pos().toShortString() + " end=" + (side == 0 ? "A" : "B") + " NO-CONNECTOR bands=" + fp.bands().size());
                        continue;
                    }
                    int dx = (int) Math.floor(p.x[i0]), dz = (int) Math.floor(p.z[i0]);
                    boolean inBand = fp.bands().contains(dx, dz);
                    int dockY = surfaceAt(level, dx, dz, Math.round(p.y[i0]));
                    int dy = dockY == Integer.MIN_VALUE ? 99 : dockY - Math.round(p.y[i0]);
                    int maxStep = 0, nonRoad = 0, prev = Integer.MIN_VALUE, samples = 0;
                    StringBuilder trail = new StringBuilder();
                    int from = side == 0 ? 0 : p.length() - 1, dir = side == 0 ? 1 : -1;
                    for (int k = 0; k < conn + 10; k++) {
                        int i = from + dir * k;
                        if (i < 0 || i >= p.length()) break;
                        int x = (int) Math.floor(p.x[i]), z = (int) Math.floor(p.z[i]);
                        int y = surfaceAt(level, x, z, Math.round(p.y[i]));
                        if (y == Integer.MIN_VALUE) {
                            nonRoad++;
                            trail.append(' ').append(x).append(',').append(z).append(":none");
                            continue;
                        }
                        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                        BlockState top = chunk.getBlockState(new BlockPos(x, y, z));
                        trail.append(' ').append(x).append(',').append(z).append(':').append(y).append('/').append(top.getBlock().getDescriptionId().replace("block.minecraft.", ""))
                            .append(k < conn ? "*" : "");
                        if (!roadBlock(top)) nonRoad++;
                        if (prev != Integer.MIN_VALUE) maxStep = Math.max(maxStep, Math.abs(y - prev));
                        prev = y;
                        samples++;
                    }
                    boolean pass = inBand && Math.abs(dy) <= 1 && maxStep <= 1 && nonRoad == 0;
                    if (pass) ok++;
                    report(src, "CR-DOCK " + (pass ? "PASS " : "FAIL ") + village.pos().toShortString() + " end=" + (side == 0 ? "A" : "B") + " conn=" + conn
                        + " dockInBand=" + inBand + " dockDy=" + dy + " maxStep=" + maxStep + " nonRoad=" + nonRoad + " samples=" + samples + " planY0=" + Math.round(p.y[i0]) + " trail:" + trail);
                }
            }
            report(src, "CR-DOCKS " + (ok == ends - noConn && noConn == 0 ? "PASS" : "FAIL") + " ends=" + ends + " ok=" + ok + " noConnector=" + noConn);
            return true;
        });
        return 1;
    }

    /** CR-STREETTOPS: street surface blocks of the band columns in the chunk holding (x, z), for the retrofit test. */
    static int tops(CommandSourceStack src, int x0, int z0) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return 0;
        int cx = x0 >> 4, cz = z0 >> 4;
        ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
        Map<String, Integer> hist = new TreeMap<>();
        var streets = n.streets(cx, cz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                boolean band = false;
                for (var b : streets) band |= b.contains(x, z);
                if (!band) continue;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) + 2;
                for (; y > chunk.getMinY() + 1; y--) {
                    BlockState st = chunk.getBlockState(pos.set(x, y, z));
                    if (st.isAir() || (!st.isSolid() && !st.is(Blocks.DIRT_PATH))) continue;
                    hist.merge(st.getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                    break;
                }
            }
        report(src, "CR-STREETTOPS chunk " + cx + "," + cz + " " + hist);
        return 1;
    }
}
