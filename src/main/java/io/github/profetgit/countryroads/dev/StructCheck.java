package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.RoadColumns;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Painted road columns inside structure piece boxes at the road's height (road y within the box, -1..+2): a road cut
 * through the structure. Village street/terminator pieces are counted apart (roads dock there on purpose).
 */
final class StructCheck {
    private StructCheck() {}

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    static int run(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-STRUCT FAIL no road network in this level");
            return 0;
        }
        BlockPos at = BlockPos.containing(src.getPosition());
        int r = radius >> 4, x0 = (at.getX() >> 4) - r, z0 = (at.getZ() >> 4) - r, side = 2 * r + 1, total = side * side;
        int[] next = {0};
        Long2IntOpenHashMap roadY = new Long2IntOpenHashMap();
        roadY.defaultReturnValue(Integer.MIN_VALUE);
        List<Object[]> starts = new ArrayList<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        DevCommands.queue(() -> {
            long until = System.nanoTime() + 45_000_000L;
            while (next[0] < total && System.nanoTime() < until) {
                int i = next[0]++;
                int cx = x0 + i % side, cz = z0 + i / side;
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet())
                    if (e.getValue().isValid()) starts.add(new Object[]{String.valueOf(registry.getKey(e.getKey())), e.getValue()});
                RoadColumns cols = RoadColumns.get(chunk);
                if (cols == null || !cols.any()) continue;
                ChunkMask m = n.mask(cx, cz);
                for (int c = 0; c < 256; c++) {
                    int lx = c & 15, lz = c >> 4;
                    if (!cols.road(lx, lz)) continue;
                    int y = m.any() ? (int) Math.floor(m.surface(ChunkMask.idx(lx, lz))) : Integer.MIN_VALUE;
                    roadY.put(BlockPos.asLong((cx << 4) + lx, 0, (cz << 4) + lz), y);
                }
            }
            if (next[0] < total) return false;
            Map<String, int[]> per = new TreeMap<>();
            int shown = 0;
            long hitCols = 0;
            for (Object[] s : starts) {
                String id = (String) s[0];
                StructureStart st = (StructureStart) s[1];
                int[] agg = per.computeIfAbsent(id, k -> new int[4]);
                agg[0]++;
                int cols = 0, streetCols = 0;
                String firstPiece = null;
                int[] firstAt = null;
                for (StructurePiece p : st.getPieces()) {
                    BoundingBox b = p.getBoundingBox();
                    boolean street = false;
                    String name = p.getClass().getSimpleName();
                    if (p instanceof PoolElementStructurePiece pe) {
                        String el = pe.getElement().toString();
                        street = el.contains("/streets/") || el.contains("/terminators/");
                        int a = el.indexOf("Left["), bb = el.indexOf(']', a + 1);
                        name = a >= 0 && bb > a ? el.substring(a + 5, bb) : el;
                    }
                    for (int x = b.minX(); x <= b.maxX(); x++)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) {
                            int y = roadY.get(BlockPos.asLong(x, 0, z));
                            if (y == Integer.MIN_VALUE || y < b.minY() - 1 || y > b.maxY() + 2) continue;
                            if (street) streetCols++;
                            else {
                                cols++;
                                if (firstAt == null) {
                                    firstAt = new int[]{x, y, z};
                                    firstPiece = name;
                                }
                            }
                        }
                }
                agg[3] += streetCols;
                if (cols > 0) {
                    agg[1]++;
                    agg[2] += cols;
                    hitCols += cols;
                    if (shown++ < 60)
                        report(src, "CR-STRUCT-HIT " + id + " start=" + st.getChunkPos().x() + "," + st.getChunkPos().z() + " cols=" + cols
                            + " at " + firstAt[0] + " " + firstAt[1] + " " + firstAt[2] + " piece=" + firstPiece);
                }
            }
            for (Map.Entry<String, int[]> e : per.entrySet()) {
                int[] v = e.getValue();
                report(src, "CR-STRUCT-TYPE " + e.getKey() + " starts=" + v[0] + " cut=" + v[1] + " cols=" + v[2] + (v[3] > 0 ? " streetCols=" + v[3] : ""));
            }
            report(src, "CR-STRUCT " + (hitCols == 0 ? "PASS" : "FAIL") + " chunks=" + total + " roadCols=" + roadY.size() + " starts=" + starts.size() + " cutCols=" + hitCols);
            return true;
        });
        return 1;
    }
}
