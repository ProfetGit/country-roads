package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.plan.ChunkMask;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Trees and huge mushrooms beside the road must stand on the ground: a log or mushroom group with nothing solid under
 * any of its blocks is floating, and natural leaves that cannot reach a log through at most 6 leaves are floating too.
 * Also counts terrain blocks above the road with air right under them inside the bank reach (overhangs the painter left).
 */
final class TreeCheck {
    static final int LEAF_REACH = 6, MAX_GROUP = 3000;

    private final ServerLevel level;
    private final Set<String> examples;
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    long floatingTrees, floatingMushrooms, floatingLeaves, overhangs, vanillaLeaves;

    TreeCheck(ServerLevel level, Set<String> examples) {
        this.level = level;
        this.examples = examples;
    }

    private BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    private static boolean wood(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static boolean shroom(BlockState s) {
        return s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK);
    }

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.NoiseColumn> base = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /**
     * The block stands where the generator's own terrain had none: something laid it over air (a bank top built on a
     * mushroom cap or a tree). Hollows under natural ground (cave carvers, hillside lips) are vanilla and not counted.
     */
    private boolean placed(int x, int y, int z) {
        var col = base.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(x, z), k -> level.getChunkSource().getGenerator()
            .getBaseColumn(x, z, level, level.getChunkSource().randomState()));
        return col.getBlock(y).isAir();
    }

    /** Where the painter puts a cut bank's top (RoadPainter.shoulder): the hillside face it leaves, not natural caves under it. */
    private static int bankTop(ChunkMask m, int c, int top) {
        return Math.max(top, Math.round(m.y[c] + Math.max(0f, -m.in[c] - 0.5f)));
    }

    /** Top blocks the painter puts on banks, shoulders and embankments. */
    private static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
            || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.PACKED_MUD);
    }

    /** Whether the loose clump this leaf belongs to touches a huge mushroom (vanilla grew one through the canopy). */
    private boolean cutByShroom(byte[] d, int k0, int w, int h, int x0, int y0, int z0) {
        int[] q = new int[512];
        java.util.BitSet seenK = new java.util.BitSet();
        int qh = 0, qt = 0;
        q[qt++] = k0;
        seenK.set(k0);
        while (qh < qt) {
            int k = q[qh++];
            int x = k % w, z = (k / w) % w, y = k / (w * w);
            if (besideShroom(x0 + x, y0 + y, z0 + z)) return true;
            int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] n : nb) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (nx < 0 || nz < 0 || ny < 0 || nx >= w || nz >= w || ny >= h) continue;
                int nk = (ny * w + nz) * w + nx;
                if (d[nk] == -1 && !seenK.get(nk) && qt < q.length) {
                    seenK.set(nk);
                    q[qt++] = nk;
                }
            }
        }
        return false;
    }

    private boolean besideShroom(int x, int y, int z) {
        return shroom(get(x + 1, y, z)) || shroom(get(x - 1, y, z)) || shroom(get(x, y + 1, z)) || shroom(get(x, y - 1, z))
            || shroom(get(x, y, z + 1)) || shroom(get(x, y, z - 1));
    }

    private static boolean natural(BlockState s) {
        return s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    private void example(String s) {
        if (examples.size() < 12) examples.add(s);
    }

    void chunk(ChunkMask m, int cx, int cz, float bankReach) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int c = 0; c < 256; c++) {
            if (m.in[c] == Float.NEGATIVE_INFINITY) continue;
            int t = (int) Math.floor(m.surface(c));
            lo = Math.min(lo, t - 12);
            hi = Math.max(hi, t + 32);
        }
        if (lo > hi) return;
        int bx = cx << 4, bz = cz << 4;
        for (int c = 0; c < 256; c++) {
            if (m.in[c] == Float.NEGATIVE_INFINITY) continue;
            int x = bx + (c & 15), z = bz + (c >> 4), t = (int) Math.floor(m.surface(c));
            for (int y = lo; y <= hi; y++) {
                BlockState s = get(x, y, z);
                if (s.isAir()) continue;
                if ((wood(s) || shroom(s)) && !seen.contains(BlockPos.asLong(x, y, z))) group(x, y, z, wood(s));
                if (m.in[c] < 0 && m.in[c] >= -bankReach && (y == t || y == bankTop(m, c, t)) && soil(s) && get(x, y - 1, z).isAir() && placed(x, y, z)) {
                    overhangs++;
                    example("overhang " + x + "," + y + "," + z + "=" + s.getBlock().getDescriptionId().replace("block.minecraft.", ""));
                }
            }
        }
        leaves(m, bx, bz, lo, hi);
    }

    /** One connected log (or mushroom) group: supported when any block of it rests on something solid of another kind. */
    private void group(int x0, int y0, int z0, boolean tree) {
        LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
        long start = BlockPos.asLong(x0, y0, z0);
        seen.add(start);
        todo.enqueue(start);
        int size = 0;
        boolean supported = false;
        while (!todo.isEmpty()) {
            long p = todo.dequeueLong();
            size++;
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            BlockState below = get(x, y - 1, z);
            if (!(tree ? wood(below) : shroom(below)) && PaintRules.solid(below) && !below.is(BlockTags.LEAVES)) supported = true;
            if (size > MAX_GROUP) {
                supported = true;
                break;
            }
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                                                long q = BlockPos.asLong(x + dx, y + dy, z + dz);
                        if (seen.contains(q)) continue;
                        BlockState s = get(x + dx, y + dy, z + dz);
                        if (tree ? wood(s) : shroom(s)) {
                            seen.add(q);
                            todo.enqueue(q);
                        }
                    }
        }
        if (!supported) {
            if (tree) floatingTrees++;
            else floatingMushrooms++;
            example((tree ? "floatingTree " : "floatingMushroom ") + x0 + "," + y0 + "," + z0 + " blocks=" + size);
        }
    }

    /** Natural leaves in the road's reach that no log holds (more than 6 leaves from any log). */
    private void leaves(ChunkMask m, int bx, int bz, int lo, int hi) {
        int r = LEAF_REACH + 1, x0 = bx - r, z0 = bz - r, y0 = lo - r, w = 16 + 2 * r, h = hi - lo + 1 + 2 * r;
        byte[] d = new byte[w * w * h];
        // 0 unknown/not leaves, 1..7 leaf distance + 1 seen, -1 leaves not reached yet
        int[] queue = new int[w * w * h];
        int qh = 0, qt = 0;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < w; z++)
                for (int x = 0; x < w; x++) {
                    BlockState s = get(x0 + x, y0 + y, z0 + z);
                    int k = (y * w + z) * w + x;
                    if (s.is(BlockTags.LOGS)) {
                        d[k] = 1;
                        queue[qt++] = k;
                    } else if (s.is(BlockTags.LEAVES)) d[k] = -1;
                }
        int[] step = {1, -1, w, -w, w * w, -w * w};
        while (qh < qt) {
            int k = queue[qh++];
            if (d[k] > LEAF_REACH) continue;
            int x = k % w, z = (k / w) % w, y = k / (w * w);
            for (int s = 0; s < 6; s++) {
                int nx = x + (s == 0 ? 1 : s == 1 ? -1 : 0), nz = z + (s == 2 ? 1 : s == 3 ? -1 : 0), ny = y + (s == 4 ? 1 : s == 5 ? -1 : 0);
                if (nx < 0 || nz < 0 || ny < 0 || nx >= w || nz >= w || ny >= h) continue;
                int q = k + step[s];
                if (d[q] == -1) {
                    d[q] = (byte) (d[k] + 1);
                    queue[qt++] = q;
                }
            }
        }
        for (int c = 0; c < 256; c++) {
            if (m.in[c] == Float.NEGATIVE_INFINITY) continue;
            int lx = c & 15, lz = c >> 4;
            for (int y = 0; y < h; y++) {
                int k = (y * w + lz + r) * w + lx + r;
                if (d[k] != -1) continue;
                int wx = x0 + lx + r, wy = y0 + y, wz = z0 + lz + r;
                if (wy < lo || wy > hi || !natural(get(wx, wy, wz))) continue;
                // vanilla's own loose leaves: generated already decaying (distance 7), or cut off by a huge mushroom
                // that grew through the canopy
                if (get(wx, wy, wz).getValue(LeavesBlock.DISTANCE) == 7 || cutByShroom(d, k, w, h, x0, y0, z0)) {
                    vanillaLeaves++;
                    continue;
                }
                floatingLeaves++;
                BlockState leaf = get(wx, wy, wz);
                example("floatingLeaves " + wx + "," + wy + "," + wz + " " + leaf.getBlock().getDescriptionId().replace("block.minecraft.", "")
                    + " d" + leaf.getValue(LeavesBlock.DISTANCE));
            }
        }
    }
}
