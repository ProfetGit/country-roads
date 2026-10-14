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
}
