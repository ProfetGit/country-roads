package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.gen.GenContext;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import io.github.profetgit.countryroads.mixin.SignBlockEntityAccessor;

/**
 * Lays the road into one chunk after its features. Every block is a function of the plan (surface Y, kind, arc
 * position) and of this column's own blocks, so neighbouring chunks always agree at the border.
 *
 * Shapes: on dry ground the road is cut or filled to the planned surface; beside it the terrain gets a 1:1 bank (cut)
 * or embankment (fill), or a masonry retaining wall where the step is too tall. Water gets a boardwalk on posts or a
 * bridge (timber up to 20 blocks, stone with arches beyond); a dry gap far below the plan (ravine) gets a trestle.
 * Tunnels keep the rock above and get timber frames, lanterns and stone portals.
 */
public final class RoadPainter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final int MAX_FILL = 4, MAX_BANK = 5;
    public static final float BANK_REACH = 5f;
    /** Cuts keep sloping back this far from the road edge (inside the mask's EdgePlan.REACH). */
    public static final float CUT_REACH = 8.5f;
    static final float CUT_SOIL = 1f, CUT_ROCK = 2f, CUT_WOBBLE = 0.8f;
    static final float VERGE_REACH = 4f;
    /** Chance (of 1024) of a pebble on a pebbly road column near the edge, and in the middle (about 1 in 9 and 1 in 34). */
    static final int PEBBLE_EDGE = 120, PEBBLE_MIDDLE = 30;

    private final Canvas chunk;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private java.util.function.IntBinaryOperator neighbourTop;

    private int chunkX, chunkZ;
    /** Columns of this chunk's roadside props: no verge plants, bushes or stones grow through them. */
    private boolean[] propCols;

    private RoadPainter(Canvas chunk) {
        this.chunk = chunk;
    }

    /** Worldgen: paints a generating chunk and records its road columns (empty too: "this chunk was handled"). */
    public static int paint(ChunkAccess chunk, ChunkMask m, java.util.List<io.github.profetgit.countryroads.world.StreetBands> streets,
                            java.util.function.IntBinaryOperator neighbourTop) {
        RoadColumns cols = RoadColumns.empty();
        if (NO_PAINT) {
            RoadColumns.store(chunk, cols);
            return 0;
        }
        Canvas canvas = Canvas.proto(chunk);
        int painted = m.any() ? paint(canvas, m, cols, neighbourTop) : 0;
        painted += StreetPainter.paint(canvas, streets, chunk.getPos().x(), chunk.getPos().z(), cols);
        RoadColumns.store(chunk, cols);
        return painted;
    }

    public static int paint(Canvas canvas, ChunkMask m, RoadColumns cols) {
        return paint(canvas, m, cols, null);
    }

    /** NO_TOP from a neighbourTop function: that column of the next chunk is not a painted connector (or not painted yet). */
    public static final int NO_TOP = Integer.MIN_VALUE;

    /**
     * neighbourTop(x, z): the surface of an already painted connector column in a neighbouring chunk, so the later of two
     * chunks can meet it (a connector laid on the ground can meet a 2-block cliff exactly on a chunk border).
     */
    public static int paint(Canvas canvas, ChunkMask m, RoadColumns cols, java.util.function.IntBinaryOperator neighbourTop) {
        if (!m.any()) return 0;
        RoadPainter p = new RoadPainter(canvas);
        p.chunkX = m.cx;
        p.chunkZ = m.cz;
        p.neighbourTop = neighbourTop;
        p.propCols = PropPainter.footprint(m);
        int painted = 0;
        GenContext.bypass(true);
        try {
            int[] ctop = new int[256];
            java.util.Arrays.fill(ctop, Integer.MIN_VALUE);
            // the road first, then banks and walls beside it (they read the finished road columns)
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 1) p.evenConnector(m, ctop);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int i = ChunkMask.idx(lx, lz);
                        float in = m.in[i];
                        if (in == Float.NEGATIVE_INFINITY) continue;
                        double q = m.surface(i);
                        int top = (int) Math.floor(q);
                        if (top <= canvas.minY() + 4 || top >= canvas.maxY() - 8) continue;
                        int x = (m.cx << 4) + lx, z = (m.cz << 4) + lz;
                        // a connector inside a village is laid on the ground, like the street it joins (the planner's ground
                        // estimate misses the village's own terrain shaping by a block or two)
                        if (m.conn[i] && m.kind[i] == EdgePlan.GROUND) {
                            // laid on the ground at the street end, easing to the planned height over the first CONN_EASE blocks
                            double u = Math.clamp(m.connD[i] / CONN_EASE, 0.0, 1.0);
                            if (u < 1) {
                                // linear: a smooth-step is half again as steep in its middle (a 6-block climb over 8 made a 2-block step)
                                double w = u;
                                int g = p.groundAt(x, z, top);
                                top = (int) Math.round(g + (Math.round(m.y[i]) - g) * w);
                                q = top;
                            }
                        }
                        if (pass == 0 && in >= 0) {
                            if (m.conn[i] && m.kind[i] == EdgePlan.GROUND) ctop[i] = top;
                            p.road(x, z, top, q - top >= 0.5, in, m, i);
                            p.settle(x, z, top, true);
                            cols.set(lx, lz, m.kind[i] != EdgePlan.TUNNEL);
                            painted++;
                        } else if (pass == 1 && in < 0) {
                            p.beside(x, z, top, -in, m, i);
                        }
                    }
                }
            }
            // bushes and field stones last, once every column of the chunk is shaped (they reach a neighbour column)
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int i = ChunkMask.idx(lx, lz);
                    if (m.in[i] >= 0 || m.in[i] == Float.NEGATIVE_INFINITY) continue;
                    p.scatter((m.cx << 4) + lx, (m.cz << 4) + lz, (int) Math.floor(m.surface(i)), -m.in[i], m, i);
                }
            for (EdgePlan.Decor d : m.decor) p.decor(d, m);
        } finally {
            GenContext.bypass(false);
        }
        return painted;
    }

    /**
     * A connector eased from the ground can still meet a cliff. First its border columns are held within one block of an
     * already painted connector column across the chunk border (this chunk is the later one, so it does the meeting: it may
     * raise or cut), then the lower column of any two-block step inside the chunk is raised (a pinned border column is never
     * moved: the higher side is cut instead).
     */
    private void evenConnector(ChunkMask m, int[] ctop) {
        boolean[] pinned = new boolean[256];
        if (neighbourTop != null) {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int i = ChunkMask.idx(lx, lz);
                    if (ctop[i] == Integer.MIN_VALUE) continue;
                    for (int[] d : dirs) {
                        int nx = lx + d[0], nz = lz + d[1];
                        if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15) continue;
                        int t = neighbourTop.applyAsInt((m.cx << 4) + nx, (m.cz << 4) + nz);
                        if (t == NO_TOP) continue;
                        int want = Math.clamp(ctop[i], t - 1, t + 1);
                        pinned[i] = true;
                        if (want != ctop[i]) {
                            ctop[i] = want;
                            road((m.cx << 4) + lx, (m.cz << 4) + lz, want, false, m.in[i], m, i);
                        }
                    }
                }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int round = 0; round < 6; round++) {
            boolean changed = false;
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int i = ChunkMask.idx(lx, lz);
                    if (ctop[i] == Integer.MIN_VALUE) continue;
                    for (int[] d : dirs) {
                        int nx = lx + d[0], nz = lz + d[1];
                        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                        int j = ChunkMask.idx(nx, nz);
                        if (ctop[j] == Integer.MIN_VALUE || ctop[j] - ctop[i] < 2) continue;
                        if (!pinned[i]) {
                            ctop[i] = ctop[j] - 1;
                            road((m.cx << 4) + lx, (m.cz << 4) + lz, ctop[i], false, m.in[i], m, i);
                        } else if (!pinned[j]) {
                            ctop[j] = ctop[i] + 1;
                            road((m.cx << 4) + nx, (m.cz << 4) + nz, ctop[j], false, m.in[j], m, j);
                        } else continue;
                        changed = true;
                    }
                }
            if (!changed) break;
        }
    }

    private void decor(EdgePlan.Decor d, ChunkMask m) {
        if (d.type() == EdgePlan.PROP) {
            PropPainter.paint(chunk, m, d);
            return;
        }
        int i = ChunkMask.idx(d.x() & 15, d.z() & 15);
        if (m.in[i] >= 0) return;
        if (d.type() == EdgePlan.ORCHARD) {
            orchardTree(d, m);
            return;
        }
        int top = (int) Math.floor(Math.round(d.y() * 2) / 2.0);
        int x = d.x(), z = d.z();
        int g = groundAt(x, z, top);
        if (Math.abs(g - top) > 4 || !PaintRules.terrain(get(x, g, z)) || woody(x, g + 1, g + 5, z)) return;
        Palette.Wood wood = Palette.wood(m.in[i] == Float.NEGATIVE_INFINITY ? 0 : m.wood[i]);
        long h = hash(x, z);
        int ax = x + d.toRoad().getStepX(), az = z + d.toRoad().getStepZ();
        clear(x, g + 1, g + 5, z);
        // a gallows post: stone foot, three fences, an arm reaching over the road edge; a hanging sign or a lantern
        // hangs from the arm. States are set exactly so the post never latches onto banks or grass beside it.
        net.minecraft.core.Direction toRoad = d.toRoad();
        BlockState foot = Palette.localStone((((h >>> 5) & 1) == 0 ? Blocks.MOSSY_COBBLESTONE_WALL : Blocks.COBBLESTONE_WALL).defaultBlockState(), pal(x, g + 1, z));
        BlockState fence = wood.fence().defaultBlockState();
        exact(x, g + 1, z, foot);
        exact(x, g + 2, z, fence);
        exact(x, g + 3, z, fence);
        exact(x, g + 4, z, fence.setValue(net.minecraft.world.level.block.CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(toRoad), true));
        BlockState arm = get(ax, g + 4, az), under = get(ax, g + 3, az);
        if ((arm.isAir() || arm.canBeReplaced()) && (under.isAir() || under.canBeReplaced())) {
            exact(ax, g + 4, az, fence.setValue(net.minecraft.world.level.block.CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(toRoad.getOpposite()), true));
            if (d.type() == EdgePlan.SIGN) hangingSign(wood, ax, g + 3, az, d);
            else exact(ax, g + 3, az, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    /** A small blossoming fruit tree with flowers in the grass around it, now and then a hay bale. */
    private void orchardTree(EdgePlan.Decor d, ChunkMask m) {
        int x = d.x(), z = d.z(), top = (int) Math.floor(Math.round(d.y() * 2) / 2.0);
        int g = groundAt(x, z, top);
        if (Math.abs(g - top) > 2 || !PaintRules.terrain(get(x, g, z)) || !get(x, g + 1, z).isAir() && !get(x, g + 1, z).canBeReplaced()) return;
        long h = hash(x, z);
        int cx = m.cx, cz = m.cz;
        for (int y = g + 1; y <= g + 3; y++) set(x, y, z, Blocks.OAK_LOG.defaultBlockState());
        for (int dy = 3; dy <= 5; dy++) {
            int r = dy == 5 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (r == 2 || ((h >>> (dx + dz + 8)) & 1) == 0)) continue;
                    if (dx == 0 && dz == 0 && dy == 3) continue;
                    int lx = x + dx, lz = z + dz;
                    if (lx >> 4 != cx || lz >> 4 != cz) continue;
                    BlockState here = get(lx, g + dy, lz);
                    if (!here.isAir() && !here.canBeReplaced()) continue;
                    long hh = hash(lx * 7 + dy, lz * 13 - dy);
                    BlockState leaf = (((hh >>> 20) & 7) < 3 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.OAK_LEAVES).defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                    set(lx, g + dy, lz, leaf);
                }
        }
        BlockState[] flowers = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(),
            Blocks.CORNFLOWER.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState()};
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                int lx = x + dx, lz = z + dz;
                if ((dx == 0 && dz == 0) || lx >> 4 != cx || lz >> 4 != cz) continue;
                long hh = hash(lx * 31 + 5, lz * 17 + 3);
                if (((hh >>> 12) & 7) > 1) continue;
                int idx = ChunkMask.idx(lx & 15, lz & 15);
                if (m.in[idx] >= -0.5f) continue;
                int gg = groundAt(lx, lz, g + 2);
                if (!get(lx, gg, lz).is(Blocks.GRASS_BLOCK) || !get(lx, gg + 1, lz).isAir()) continue;
                set(lx, gg + 1, lz, flowers[(int) Math.floorMod(hh >>> 20, (long) flowers.length)]);
            }
        if (((h >>> 30) & 3) == 0) {
            int lx = x + 2, lz = z;
            if (lx >> 4 == cx) {
                int gg = groundAt(lx, lz, g + 2);
                if (PaintRules.terrain(get(lx, gg, lz)) && get(lx, gg + 1, lz).isAir() && m.in[ChunkMask.idx(lx & 15, lz & 15)] < -0.5f)
                    set(lx, gg + 1, lz, Blocks.HAY_BLOCK.defaultBlockState());
            }
        }
    }

    private void exact(int x, int y, int z, BlockState s) {
        if (y <= chunk.minY() || y >= chunk.maxY()) return;
        chunk.setExact(pos.set(x, y, z), s);
    }

    /** Front (toward this village, read when leaving): destination and distance. Back: welcome to this village. */
    private void hangingSign(Palette.Wood wood, int x, int y, int z, EdgePlan.Decor d) {
        double theta = Math.atan2(d.along().getStepX(), -d.along().getStepZ());
        int rot = Math.floorMod((int) Math.round(Math.toDegrees(theta) / 22.5), 16);
        BlockState sign = wood.hangingSign().defaultBlockState()
            .setValue(net.minecraft.world.level.block.CeilingHangingSignBlock.ROTATION, rot)
            .setValue(net.minecraft.world.level.block.CeilingHangingSignBlock.ATTACHED, true);
        exact(x, y, z, sign);
        var be = new net.minecraft.world.level.block.entity.HangingSignBlockEntity(new BlockPos(x, y, z), sign);
        SignBlockEntityAccessor raw = (SignBlockEntityAccessor) be;
        // merged posts carry "name\ndistance" pairs: up to two destinations, two lines each
        String[] a = d.front1().split("\n", 2), b = d.front2().split("\n", 2);
        raw.countryroads$setFront(a.length == 2 ? text(a[0], a[1], b[0], b.length == 2 ? b[1] : "") : text("", d.front1(), d.front2(), ""));
        String[] back = d.back().split("\n", 2);
        raw.countryroads$setBack(back.length == 2 ? text(back[0], back[1], "", "") : text("Welcome to", d.back(), "", ""));
        raw.countryroads$setWaxed(true);
        chunk.blockEntity(be);
    }

    /** A hedgerow along the verge, one to two leaves high with gaps, wildflowers along its foot on the road side. */
    private void hedge(int x, int z, int top, float d, int g, ChunkMask m, int i, int s, long h) {
        if (g != top) return;
        if (d >= 0.6f && d < 1.0f) {
            if (((h >>> 22) & 7) == 0 && get(x, g, z).is(Blocks.GRASS_BLOCK) && get(x, g + 1, z).isAir()) {
                BlockState[] f = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState()};
                set(x, g + 1, z, f[(int) Math.floorMod(h >>> 40, 4L)]);
            }
            return;
        }
        if (d < 1.0f || d >= 2.1f || Math.floorMod(s, 19) == 0) return;
        if (tree(x, g + 1, g + 2, z)) return;
        BlockState leaf = hedgeLeaves(m.wood[i], h).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        clear(x, g + 1, g + 2, z);
        set(x, g + 1, z, leaf);
        if (((h >>> 18) & 7) < 5) set(x, g + 2, z, leaf);
    }

    private static BlockState hedgeLeaves(int wood, long h) {
        return switch (wood) {
            case 0 -> Blocks.SPRUCE_LEAVES.defaultBlockState();
            case 2 -> Blocks.MANGROVE_LEAVES.defaultBlockState();
            case 3 -> Blocks.DARK_OAK_LEAVES.defaultBlockState();
            case 4 -> Blocks.JUNGLE_LEAVES.defaultBlockState();
            default -> (((h >>> 26) & 7) == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : ((h >>> 26) & 7) < 3 ? Blocks.AZALEA_LEAVES : Blocks.OAK_LEAVES).defaultBlockState();
        };
    }

    /** Chances (of 1024, before the patch noise) of a roadside bush and of a field stone on a verge column. */
    static final int BUSH = 70, STONE = 9;

    /**
     * Roadside bushes and field stones, 2.2-4.6 blocks out from the road edge on level soil: a bush is one to four leaf
     * blocks of the biome's hedge leaves (persistent), now and then two high; a field stone is a stone or andesite block
     * with a slab of the same stone beside it. Bushes come in patches (smooth noise), stones are rare. Everything stays
     * inside this chunk, so neighbouring chunks never disagree, and is placed after the chunk's shaping is done.
     */
    private void scatter(int x, int z, int top, float d, ChunkMask m, int i) {
        if (d < 2.2f || d > 4.6f || m.kind[i] != EdgePlan.GROUND || m.conn[i] || propCols != null && propCols[i]) return;
        long h = hash(x * 13 + 5, z * 7 - 11);
        int r = (int) (h & 1023), patch = clump(x + 31, z - 17);
        boolean stone = r < STONE, bush = !stone && r < STONE + BUSH * patch * patch / (1024 * 1024 / 2);
        if (!stone && !bush) return;
        int g = groundAt(x, z, top);
        if (Math.abs(g - top) > 2 || !verge(x, g, z)) return;
        Palette.Surface pal = pal(x, g, z);
        boolean dry = pal == Palette.DESERT || pal == Palette.BADLANDS;
        net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST};
        int start = (int) ((h >>> 20) & 3);
        if (stone) {
            boolean andesite = ((h >>> 24) & 1) == 0;
            for (int k = 0; k < 4; k++) {
                net.minecraft.core.Direction dir = dirs[(start + k) & 3];
                int sx = x + dir.getStepX(), sz = z + dir.getStepZ();
                if (!inChunk(sx, sz) || !verge(sx, g, sz) || !besideOnly(sx, sz, m)) continue;
                place(x, g + 1, z, (andesite ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState());
                // the slab is now and then the other stone, so the pair reads as two stones, not a cut block
                boolean other = ((h >>> 26) & 3) == 0;
                place(sx, g + 1, sz, ((andesite ^ other) ? Blocks.ANDESITE_SLAB : Blocks.STONE_SLAB).defaultBlockState());
                return;
            }
            return;
        }
        if (dry) return;
        BlockState leaf = hedgeLeaves(m.wood[i], h).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        place(x, g + 1, z, leaf);
        if (((h >>> 30) & 3) == 0 && get(x, g + 2, z).isAir()) set(x, g + 2, z, leaf);
        int more = (int) ((h >>> 33) % 4);
        for (int k = 0; k < 4 && more > 0; k++) {
            net.minecraft.core.Direction dir = dirs[(start + k) & 3];
            int sx = x + dir.getStepX(), sz = z + dir.getStepZ();
            if (!inChunk(sx, sz) || !verge(sx, g, sz) || !besideOnly(sx, sz, m)) continue;
            place(sx, g + 1, sz, leaf);
            more--;
        }
    }

    /** Soil a bush or stone can stand on at y, with room above (plants and snow get replaced), no tree in the way. */
    private boolean verge(int x, int y, int z) {
        if (propCols != null && inChunk(x, z) && propCols[ChunkMask.idx(x & 15, z & 15)]) return false;
        BlockState ground = get(x, y, z), above = get(x, y + 1, z);
        if (!Palette.plantable(Blocks.SHORT_GRASS.defaultBlockState(), ground)) return false;
        if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return false;
        return !tree(x, y + 1, y + 3, z);
    }

    /** A neighbour column off the road and its rails (the road, a fence or a wall line is never covered). */
    private static boolean besideOnly(int x, int z, ChunkMask m) {
        float in = m.in[ChunkMask.idx(x & 15, z & 15)];
        return in == Float.NEGATIVE_INFINITY || in < -2.0f && m.kind[ChunkMask.idx(x & 15, z & 15)] == EdgePlan.GROUND;
    }

    private boolean inChunk(int x, int z) {
        return x >> 4 == chunkX && z >> 4 == chunkZ;
    }

    /** Replaces a plant (both halves of a tall one) or snow layer, then sets the block. */
    private void place(int x, int y, int z, BlockState s) {
        BlockState here = get(x, y, z);
        if (!here.isAir() && Palette.upperHalf(here) != null && get(x, y + 1, z).is(here.getBlock())) set(x, y + 1, z, AIR);
        set(x, y, z, s);
    }

    /** Dry-stone wall or split-rail fence on the shoulder, where a run says so and the shoulder is level with the road. */
    private void wallOrFence(int x, int z, int top, float d, ChunkMask m, int i) {
        if (m.run[i] < 0 || m.kind[i] != EdgePlan.GROUND) return;
        int g = groundAt(x, z, top);
        if (!PaintRules.terrain(get(x, g, z)) || tree(x, g + 1, g + 3, z)) return;
        int s = (int) Math.floor(m.s[i]);
        long h = hash(x, z);
        if (m.run[i] == EdgePlan.STONE_WALL && g == top && (d >= 2.1f && d < 3.1f && ((h >>> 50) & 7) < 3 || d >= 0.6f && d < 1.0f && ((h >>> 50) & 15) < 2)) {
            // the wall is not a single line: loose stones and a few low courses either side of it
            clear(x, g + 1, g + 2, z);
            set(x, g + 1, z, Palette.localStone(((h >>> 54) & 3) == 0 ? Palette.wallBody(h) : Palette.wallCap(h >>> 3) == null ? Blocks.COBBLESTONE_SLAB.defaultBlockState()
                : Palette.wallCap(h >>> 3).hasProperty(net.minecraft.world.level.block.StairBlock.FACING) ? Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState() : Palette.wallCap(h >>> 3), pal(x, g, z)));
            return;
        }
        if (m.run[i] == EdgePlan.HEDGE) {
            hedge(x, z, top, d, g, m, i, s, h);
            return;
        }
        if (!onLine(d, m.slant[i])) return;
        if (m.run[i] == EdgePlan.FENCE ? g != top : Math.abs(g - top) > 1) return;
        if (m.run[i] == EdgePlan.STONE_WALL) {
            if (Math.floorMod(s, 17) == 0) return;
            clear(x, g + 1, g + 3, z);
            boolean low = ((h >>> 3) & 7) == 0;
            Palette.Surface stone = pal(x, g, z);
            if (!low) set(x, g + 1, z, Palette.localStone(Palette.wallBody(h), stone));
            BlockState cap = Palette.localStone(Palette.wallCap(h >>> 7), stone);
            if (cap != null) {
                if (cap.hasProperty(net.minecraft.world.level.block.StairBlock.FACING))
                    cap = cap.setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(net.minecraft.util.RandomSource.create(h)));
                set(x, low ? g + 1 : g + 2, z, cap);
            }
        } else {
            if (Math.floorMod(s, 23) == 11) return;
            clear(x, g + 1, g + 2, z);
            set(x, g + 1, z, Palette.wood(m.wood[i]).fence().defaultBlockState());
        }
    }

    /**
     * Whether the column lies on the fence line 1.5 blocks out from the road edge, as a 4-connected digital line: one
     * column across on straight stretches, a clean staircase on diagonals, never two side by side (fences would close
     * into little squares).
     */
    static boolean onLine(float d, float slant) {
        float h = slant / 2;
        return d - LINE >= -h && d - LINE < h;
    }

    static final float LINE = 1.5f;

    private static SignText text(String a, String b, String c, String d) {
        List<Component> lines = List.of(Component.literal(a), Component.literal(b), Component.literal(c), Component.literal(d));
        return new SignText(lines, lines, DyeColor.BLACK, false);
    }

    private BlockState get(int x, int y, int z) {
        return chunk.get(pos.set(x, y, z));
    }

    private void set(int x, int y, int z, BlockState s) {
        if (y <= chunk.minY() || y >= chunk.maxY()) return;
        chunk.set(pos.set(x, y, z), s);
    }

    private void clear(int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            BlockState here = get(x, y, z);
            if (here.isAir()) continue;
            if (here.is(BlockTags.LOGS)) chunk.beforeRemovingLog(pos.set(x, y, z).immutable());
            if (TRACE && (io.github.profetgit.countryroads.gen.GenContext.woody(here) || here.is(BlockTags.LEAVES)))
                io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE paintclear " + x + "," + y + "," + z + " " + here.getBlock().getDescriptionId(), new Throwable("where"));
            if (!get(x, y, z).isAir()) set(x, y, z, AIR);
        }
    }

    /** Blocks over which a connector eases from the ground at its street end to the road's planned height. */
    public static final double CONN_EASE = 8;

    static final String DEBUG = System.getProperty("countryroads.debugColumn", "");
    /** Tests only: plan everything but paint nothing (the land as it would be without roads, for CutCheck's reference). */
    public static final boolean NO_PAINT = Boolean.getBoolean("countryroads.noPaint");
    static final boolean TRACE = Boolean.getBoolean("countryroads.traceClear");
    static final boolean TRACE_LAMPS = Boolean.getBoolean("countryroads.traceLamps");

    private String dump(int x, int z, int top) {
        StringBuilder sb = new StringBuilder();
        for (int y = top + 3; y >= top - 6; y--) sb.append(' ').append(y).append('=').append(get(x, y, z).getBlock().getDescriptionId().replace("block.minecraft.", ""));
        return sb.toString();
    }

    private void road(int x, int z, int top, boolean half, float in, ChunkMask m, int i) {
        boolean dbg = DEBUG.equals(x + "," + z);
        if (dbg) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE before " + x + "," + z + " top=" + top + " ground=" + groundAt(x, z, top) + dump(x, z, top));
        road0(x, z, top, half, in, m, i);
        if (dbg) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE after" + dump(x, z, top));
    }

    private void road0(int x, int z, int top, boolean half, float in, ChunkMask m, int i) {
        long h = hash(x, z);
        int ground = groundAt(x, z, top);
        byte kind = m.kind[i];
        int s = (int) Math.floor(m.s[i]);
        Palette.Wood wood = Palette.wood(m.wood[i]);
        // fill too high for an embankment: next to a stone bridge a masonry abutment; anywhere else the road stays solid
        // ground (earth, then rubble), never a few loose timber bridge columns in the middle of a road
        if (kind == EdgePlan.GROUND && top - ground - 1 > MAX_FILL && m.bridge[i] == EdgePlan.STONE) kind = EdgePlan.BRIDGE;
        if (kind == EdgePlan.TUNNEL && ground <= top + 5) kind = EdgePlan.GROUND;

        switch (kind) {
            case EdgePlan.BOARDWALK -> walkway(x, z, top, -in, m, i);
            case EdgePlan.BRIDGE -> bridge(x, z, top, half, -in, m, i, ground);
            case EdgePlan.TUNNEL -> {
                set(x, top, z, Palette.surface(pal(x, top, z), in, h, Blocks.STONE.defaultBlockState(), false));
                fillBelow(x, z, ground, top);
                boolean frame = Math.floorMod(s, 6) == 0;
                clear(x, top + 1, frame ? top + 3 : top + 4, z);
                if (frame) set(x, top + 4, z, wood.planks().defaultBlockState());
                else if (Math.floorMod(s, 12) == 6 && in > 0.8f) set(x, top + 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                if (!PaintRules.solid(get(x, top + 5, z))) set(x, top + 5, z, Blocks.STONE.defaultBlockState());
                if (m.runPos[i] <= 1 && ground > top + 7) set(x, top + 5, z, Palette.masonry(h));
            }
            default -> {
                BlockState natural = get(x, ground, z);
                fillBelow(x, z, ground, top);
                Palette.Surface pal = pal(x, top, z);
                // overhangs over caves: the surface gets a solid block under it (gravel and sand would fall)
                if (!PaintRules.solid(get(x, top - 1, z))) set(x, top - 1, z, Blocks.DIRT.defaultBlockState());
                set(x, top, z, Palette.surface(pal, in, h, natural, ground == top));
                int from = top + 1;
                if (half) {
                    set(x, top + 1, z, pal.step().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    from++;
                }
                // everything above the road goes, up to the plants that stood on the old ground
                clear(x, from, Math.max(top + 3, ground + 2), z);
                // a pebble now and then on stony stretches, mostly toward the edges where wheels do not run
                if (!half && Palette.pebbly(get(x, top, z)) && (int) ((h >>> 50) & 1023) < (in < 1f ? PEBBLE_EDGE : PEBBLE_MIDDLE))
                    set(x, top + 1, z, Palette.pebble((int) (h >>> 12)));
            }
        }
    }

    /** Target spans: stone arches between piers, timber bents. */
    static final float STONE_SPAN = 9f, TIMBER_BAY = 5f;

    /**
     * One column of a bridge. d: distance outside the road edge (negative on the road). The parapet runs on a
     * 4-connected line half a block outside the edge (like the fence line, so diagonals never double up); the deck lies
     * inside it, and the next line out carries the outer details (cutwaters, corbels, vines). Everything along the
     * bridge is placed by u, the column's place in the crossing (0..runLen), so both sides of a row agree and piers,
     * bents and lanterns face each other. Style, wood, arch rise and water level are per crossing.
     */
    private void bridge(int x, int z, int top, boolean half, float d, ChunkMask m, int i, int ground) {
        long h = hash(x, z);
        float sl = m.slant[i] / 2;
        boolean rail = d >= 0 && d - 0.5f >= -sl && d - 0.5f < sl, deck = !rail && d - 0.5f < sl;
        boolean outer = !rail && !deck && d - 0.5f < 3 * sl;
        // a stretch the painter turned into a bridge (fill too high) has no crossing data: a plain timber trestle
        boolean planned = m.kind[i] == EdgePlan.BRIDGE;
        float L = planned ? m.runLen[i] : 1000f, u = planned ? m.runU[i] : (float) m.s[i];
        // samples per block step along the rail line: on a diagonal its blocks advance only 0.7 of a block along the road,
        // so a one-block row is narrower there (else both blocks of a stair step took the post, and two lanterns)
        float ds = m.ds[i] * Math.max(Math.abs(m.tx[i]), Math.abs(m.tz[i]));
        Direction along = Direction.getApproximateNearest(m.tx[i], 0, m.tz[i]);
        Direction out = Direction.getApproximateNearest(m.ox[i], 0, m.oz[i]);
        if (out.getAxis() == along.getAxis() || out.getAxis() == Direction.Axis.Y) out = along.getClockWise();
        // the crossing's water level (its ground on a dry trestle): weathering grows up from it
        int level = top - 2 - m.bridgeRise[i];
        // grass the bridge now shelters loses its frost
        BlockState g = get(x, ground, z);
        if (g.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
            && g.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY) && !get(x, ground + 1, z).is(Blocks.SNOW))
            set(x, ground, z, g.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, false));
        if (planned && !rail && !deck) lilyPad(x, z, level);
        if (m.bridge[i] == EdgePlan.STONE) {
            // fill too high beside a stone bridge's raised approach: a solid masonry abutment with a plain parapet
            stoneBridge(x, z, top, half, d, rail, deck, outer, L, u, ds, m.bridgeRise[i], along, out, Math.max(level, planned ? level : ground), ground, h, !planned,
                rail ? prevU(d, m, i) : Float.NaN, m.joint[i]);
        } else {
            // the road's deck wood (spruce, oak, mangrove, dark oak, jungle) as a bridge pair when the crossing has none
            int pair = planned ? m.bridgeWood[i] : new int[]{2, 0, 6, 3, 4}[Math.clamp(m.wood[i], 0, 4)];
            timberBridge(x, z, top, half, rail, deck, outer, L, u, ds, planned, Palette.bridgeWood(pair, false), Palette.bridgeWood(pair, true),
                along, out, level, ground, h);
        }
    }

    /**
     * How weathered a block of a bridge is: 0 dry, 1 mossy, 2 thick. Moss climbs from the water (or the ground) in a
     * wavy line 1-4 blocks up, higher on shaded faces (north, under the arches), then thins out with height; dry stone
     * above. Smooth noise along the face keeps it in patches, never a random sprinkle.
     */
    private static int moss(int x, int y, int z, int base, float shade) {
        float line = base + 1f + shade + 2.8f * clump(x * 2 + 11, z * 2 - 7) / 1024f;
        float above = y - line;
        int r = (int) ((hash(x * 7 + y * 131, z * 13 - y * 17) >>> 22) & 1023);
        if (above < -1.5f) return r < 820 ? 2 : 1;
        if (above < 0) return r < 880 ? 1 : 0;
        return r < (int) (360 * Math.exp(-above / 1.2)) ? 1 : 0;
    }

    /** Lily pads in loose clusters on still water beside a bridge (not where the water freezes). */
    private void lilyPad(int x, int z, int level) {
        if (clump(x * 2 + 3, z * 2 + 17) < 620 || rnd(x, level + 1, z) > 260) return;
        BlockState w = get(x, level, z);
        if (!w.is(Blocks.WATER) || !w.getFluidState().isSource() || !get(x, level + 1, z).isAir()) return;
        BlockPos at = new BlockPos(x, level + 1, z);
        if (chunk.biome(x, level + 1, z).value().coldEnoughToSnow(at, 63)) return;
        set(x, level + 1, z, Blocks.LILY_PAD.defaultBlockState());
    }

    /**
     * A standing lantern, unless another one already stands next to it (any of the 26 blocks around, inside this chunk):
     * two lanterns side by side are never wanted. Returns whether it was placed.
     */
    private boolean lantern(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    if ((dx | dy | dz) == 0 || (x + dx) >> 4 != x >> 4 || (z + dz) >> 4 != z >> 4) continue;
                    if (get(x + dx, y + dy, z + dz).is(Blocks.LANTERN)) return false;
                }
        set(x, y, z, Blocks.LANTERN.defaultBlockState());
        return true;
    }

    private static int rnd(int x, int y, int z) {
        return (int) ((hash(x * 31 + y * 97, z * 17 + y * 53) >>> 40) & 1023);
    }

    /** One bridge stone at (x, y, z) in its role, weathered from base. */
    private void stone(int x, int y, int z, int role, int base, float shade) {
        int mo = role == Palette.PAVING || role == Palette.FOOTING ? 0 : moss(x, y, z, base, shade);
        set(x, y, z, Palette.bridgeStone(role, mo, rnd(x, y, z)));
    }

    private static BlockState stair(BlockState s, Direction facing, boolean upsideDown) {
        return s.setValue(net.minecraft.world.level.block.StairBlock.FACING, facing)
            .setValue(net.minecraft.world.level.block.StairBlock.HALF, upsideDown ? net.minecraft.world.level.block.state.properties.Half.TOP
                : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
    }

    /**
     * Stone: dressed stone brick piers, arch rings and parapet; spandrel walls and abutments in rubble (cobble, stone,
     * andesite); worn paving on the deck; cobble footings under water. Moss rises from the water line (see moss()).
     */
    /**
     * u of the column before this one on its rail line (a 4-connected digital line: the neighbour one block back along x
     * or z that is on the line too), NaN when there is none. On a diagonal both blocks of a stair step can fall in one
     * row's band; only the first of them takes the row's pilaster and lantern (two lanterns stood side by side, across a
     * chunk border where the lantern guard cannot see).
     */
    private static float prevU(float d, ChunkMask m, int i) {
        float sl = m.slant[i] / 2, best = Float.NaN;
        float sx = Math.signum(m.tx[i]), sz = Math.signum(m.tz[i]);
        float[][] c = {{sx, 0}, {0, sz}};
        for (float[] st : c) {
            if (st[0] == 0 && st[1] == 0) continue;
            float dc = d - st[0] * m.ox[i] - st[1] * m.oz[i];
            if (dc - 0.5f < -sl || dc - 0.5f >= sl) continue;
            float uc = m.runU[i] - m.ds[i] * (Math.abs(st[0] * m.tx[i]) + Math.abs(st[1] * m.tz[i]));
            if (Float.isNaN(best) || uc > best) best = uc;
        }
        return best;
    }

    private void stoneBridge(int x, int z, int top, boolean half, float d, boolean rail, boolean deck, boolean outer, float L, float u, float ds, int rise,
                             Direction along, Direction out, int base, int ground, long h, boolean solid, float uPrev, boolean joint) {
        int spans = Math.max(1, Math.round(L / STONE_SPAN));
        float sp = L / spans;
        int j = Math.clamp(Math.round(u / sp), 0, spans);
        boolean inner = j > 0 && j < spans;
        // rows are counted in blocks, not samples: a feature one block wide spans ds samples (posts and lanterns doubled up
        // where a straightened line packed its samples closer than a block)
        boolean pier = !solid && inner && Math.abs(u - j * sp) < ds, abut = solid || u < 1.5f * ds || u > L - 1.5f * ds;
        float north = out == Direction.NORTH ? 0.8f : out == Direction.SOUTH ? -0.3f : 0f;
        int under = pier || abut ? ground : archUnder(u, sp, spans, rise, top);
        if (!rail && !deck) {
            if (!outer) {
                // a starling of rubble around each pier foot, under the water
                if (!solid && inner && Math.abs(u - j * sp) < 1.5f * ds && d < 2.6f)
                    for (int y = ground + 1; y <= base && y < top - 2; y++) set(x, y, z, Palette.bridgeStone(Palette.FOOTING, 0, rnd(x, y, z)));
                return;
            }
            if (pier) {
                // cutwater: the pier stands a block proud of the parapet, its top sloping back toward the bridge
                for (int y = ground + 1; y <= top - 2; y++) {
                    if (y <= base) set(x, y, z, Palette.bridgeStone(Palette.FOOTING, 0, rnd(x, y, z)));
                    else stone(x, y, z, Palette.DRESSED, base, north);
                }
                set(x, top - 1, z, stair(Palette.bridgeStair(moss(x, top - 1, z, base, north)), out.getOpposite(), false));
                clear(x, top, top + 2, z);
                return;
            }
            if (solid) return;
            // corbels: a lip of upside-down stairs under the parapet, and ivy climbing the wall from the water
            BlockState at = get(x, top, z);
            if (at.isAir() || at.canBeReplaced()) set(x, top, z, stair(Palette.bridgeStair(0), out.getOpposite(), true));
            if (clump(x * 3 + 5, z * 3 + 9) > 560) {
                BlockState vine = Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.getPropertyForFace(out.getOpposite()), true);
                for (int y = Math.max(base + 1, abut ? base + 1 : under); y < top; y++) {
                    if (moss(x, y, z, base, north + 0.5f) == 0 || !get(x, y, z).isAir()) break;
                    set(x, y, z, vine);
                }
            }
            return;
        }
        boolean face = rail;
        set(x, top, z, deck ? Palette.bridgeStone(Palette.PAVING, 0, rnd(x, top, z)) : Palette.bridgeStone(Palette.DRESSED, 0, rnd(x, top, z)));
        if (pier || abut) {
            int role = pier ? Palette.DRESSED : Palette.RUBBLE;
            for (int y = top - 1; y > ground && y > top - 64; y--) {
                if (y <= base && !solid) set(x, y, z, Palette.bridgeStone(Palette.FOOTING, 0, rnd(x, y, z)));
                else stone(x, y, z, role, base, face ? north : 0.6f);
            }
        } else {
            // spandrel rubble over a dressed arch ring two blocks deep; the underside is shaded, so mossier
            for (int y = top - 1; y >= under; y--)
                stone(x, y, z, y <= under + 1 ? Palette.DRESSED : Palette.RUBBLE, base, y <= under + 1 ? 1.5f + (face ? north : 0) : face ? north : 0);
            // the underside steps up toward the crown: an upside-down stair turns each step into a curve
            float c = (Math.clamp((int) Math.floor(u / sp), 0, spans - 1) + 0.5f) * sp;
            float toward = u > c ? -1 : 1;
            if (under < top - 1 && archUnder(u + toward, sp, spans, rise, top) > under)
                set(x, under, z, stair(Palette.bridgeStair(moss(x, under, z, base, 1.5f)), toward < 0 ? along : along.getOpposite(), true));
        }
        if (deck) {
            if (half) set(x, top + 1, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
            clear(x, top + (half ? 2 : 1), top + 4, z);
            return;
        }
        // parapet with a slab coping (rain-fed moss on a few); a taller pilaster over each pier and at both ends, every
        // other one with a lantern
        int k = Math.clamp(Math.round(u / sp), 0, spans);
        boolean end = u < ds || u > L - ds;
        boolean pilaster = !solid && (end || Math.abs(u - k * sp) < ds / 2 && k > 0 && k < spans);
        // the second block of a stair step in the same row: a plain parapet. Where the road bends at a bridge end, the
        // columns on the outer side all sit at the joint's u, so the step estimate cannot tell them apart: none of those
        if (end && joint) pilaster = false;
        if (pilaster && !Float.isNaN(uPrev) && uPrev >= 0) {
            boolean prevEnd = uPrev < ds || uPrev > L - ds;
            if (end ? prevEnd : Math.abs(uPrev - k * sp) < ds / 2) pilaster = false;
        }
        stone(x, top + 1, z, Palette.DRESSED, base, north);
        int r = rnd(x, top + 2, z);
        if (pilaster) {
            set(x, top + 2, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            boolean lamp = end || k % 2 == 0 || spans <= 2;
            if (TRACE_LAMPS) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-LAMP " + x + "," + z + " u=" + u + " L=" + L + " ds=" + ds + " uPrev=" + uPrev + " end=" + end + " k=" + k + " lamp=" + lamp);
            if (!lamp || !lantern(x, top + 3, z)) set(x, top + 3, z, Palette.bridgeSlab(0, r));
            clear(x, top + 4, top + 4, z);
        } else {
            set(x, top + 2, z, Palette.bridgeSlab(r < 90 ? 1 : 0, r));
            clear(x, top + 3, top + 4, z);
        }
    }

    /**
     * A tiny walkway over shallow still water (the boardwalk kind), after the user's own build (tinybridge.litematic):
     * one layer of flat spruce pieces just above the water, following the road's line, each column a spruce bottom slab
     * (60%), a closed spruce trapdoor lying flat in any direction (27%) or an unlit campfire (13%); no rails, posts or
     * piles. Columns just outside the road edge are paved about half the time, so the edges are ragged like the build's.
     */
    private void walkway(int x, int z, int top, float d, ChunkMask m, int i) {
        long h = hash(x * 3 + 17, z * 5 - 29);
        boolean edge = d >= 0 && d < 1f;
        if (d >= 0 && !(edge && ((h >>> 50) & 1023) < 470)) {
            lilyPad(x, z, top - 1);
            return;
        }
        int r = (int) ((h >>> 20) & 1023);
        Direction facing = Direction.from2DDataValue((int) ((h >>> 40) & 3));
        BlockState piece;
        if (r < 615) piece = Blocks.SPRUCE_SLAB.defaultBlockState();
        else if (r < 890) piece = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, facing)
            .setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, false)
            .setValue(net.minecraft.world.level.block.TrapDoorBlock.HALF, net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        else piece = Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false)
            .setValue(net.minecraft.world.level.block.CampfireBlock.FACING, facing);
        set(x, top, z, piece);
        clear(x, top + 1, top + 3, z);
    }

    /** Lowest masonry block under the deck at u: a flattened round arch between piers, crown one block under the deck. */
    private static int archUnder(float u, float sp, int spans, int rise, int top) {
        int j = Math.clamp((int) Math.floor(u / sp), 0, spans - 1);
        float c = (j + 0.5f) * sp, halfW = Math.max(1f, sp / 2 - 1f);
        float r = Math.min(rise, Math.min(5f, halfW));
        float q = Math.min(1f, Math.abs(u - c) / halfW);
        return top - 1 - Math.round(r * (1 - (float) Math.sqrt(1 - q * q)));
    }

    /**
     * Timber: plank deck (a few planks replaced in the darker trim wood over the years) edged by trim stripped-log
     * stringers that carry the rail, knee braces under them either side of each bent, cross beams under the deck at the
     * bents, log posts driven down to the river bed, ivy climbing the posts from the water, earth under the ends.
     */
    private void timberBridge(int x, int z, int top, boolean half, boolean rail, boolean deck, boolean outer, float L, float u, float ds, boolean planned,
                              Palette.BridgeWood main, Palette.BridgeWood trim, Direction along, Direction out, int level, int ground, long h) {
        int bays = Math.max(1, Math.round((L - 1) / TIMBER_BAY));
        float step = (L - 1) / bays;
        int j = Math.clamp(Math.round((u - 0.5f) / step), 0, bays);
        float off = u - 0.5f - j * step;
        boolean bent = planned ? Math.abs(off) < ds / 2 : Math.abs(u - 5 * Math.round(u / 5)) < ds / 2;
        boolean end = planned && (u < ds || u > L - ds);
        if (!rail && !deck) {
            // ivy on the outer face of a post, up from the water
            if (outer && planned && bent && clump(x * 3 + 5, z * 3 + 9) > 480) {
                BlockState vine = Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.getPropertyForFace(out.getOpposite()), true);
                for (int y = level + 1; y < top; y++) {
                    if (moss(x, y, z, level, 0.3f) == 0 || !get(x, y, z).isAir()) break;
                    set(x, y, z, vine);
                }
            }
            return;
        }
        Direction.Axis alongAxis = along.getAxis(), across = alongAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        set(x, top, z, rail ? trim.stripped().setValue(RotatedPillarBlock.AXIS, alongAxis) : deck && rnd(x, top, z) < 70 ? trim.planks() : main.planks());
        if (planned && (u < 1.5f || u > L - 1.5f) && top - 1 - ground <= 3) {
            // the bank runs on under the bridge's ends
            for (int y = top - 1; y > ground; y--) set(x, y, z, Blocks.DIRT.defaultBlockState());
        } else if (rail) {
            // knee braces either side of each bent, under the stringer
            if (planned && !bent && Math.abs(off) < 1.5f * ds && get(x, top - 1, z).isAir())
                set(x, top - 1, z, stair(trim.stairs(), off > 0 ? along.getOpposite() : along, true));
        } else if (bent) {
            set(x, top - 1, z, trim.stripped().setValue(RotatedPillarBlock.AXIS, across));
        }
        if (deck) {
            if (half) set(x, top + 1, z, main.slab());
            clear(x, top + (half ? 2 : 1), top + 4, z);
            return;
        }
        if (bent) {
            // a post through the deck edge, from the bed to above the rail, footed in stone where it stands in water
            timberPost(x, z, top + 2, top, main.log(), main.stripped());
            boolean lamp = planned && (end || j % 2 == 0) && lantern(x, top + 3, z);
            clear(x, top + (lamp ? 4 : 3), top + 4, z);
        } else {
            set(x, top + 1, z, main.fence());
            clear(x, top + 2, top + 4, z);
        }
    }

    /**
     * A log post from y down through the water, driven a block into the river bed. Over a gap too deep to reach (a
     * ravine) the post stops at the deck: a stripped log from the rail to the deck edge instead of a log hanging free.
     */
    private void timberPost(int x, int z, int y, int top, BlockState log, BlockState stripped) {
        int bed = Integer.MIN_VALUE;
        for (int k = y; k > chunk.minY() + 1 && k > y - 64; k--) {
            BlockState here = get(x, k, z);
            if (PaintRules.solid(here) && here.getFluidState().isEmpty() && !here.is(BlockTags.PLANKS) && !here.is(BlockTags.LOGS)) {
                bed = k;
                break;
            }
        }
        if (bed == Integer.MIN_VALUE) {
            for (int k = y; k >= top; k--) set(x, k, z, stripped);
            return;
        }
        for (int k = y; k > bed; k--) set(x, k, z, log);
        if (PaintRules.terrain(get(x, bed, z)) && PaintRules.solid(get(x, bed - 1, z))) set(x, bed, z, log);
    }

    /** Columns beside the road: rails, tunnel walls, banks, embankments, retaining walls. */
    private void beside(int x, int z, int top, float d, ChunkMask m, int i) {
        if (d >= CUT_REACH) return;
        if (d >= BANK_REACH) {
            // past the bank reach only a cut keeps going, until its slope meets the hillside (no sheer face at the reach)
            if (m.kind[i] != EdgePlan.GROUND) return;
            int ground = groundAt(x, z, top);
            if (ground <= top + 1) return;
            cutSlope(x, z, top, d, ground, m.y[i]);
            settle(x, z, top, false);
            return;
        }
        besideShape(x, z, top, d, m, i);
        settle(x, z, top, false);
    }

    /**
     * Grass, podzol and mycelium show frost sides when "snowy": keep that flag true only under snow. In cold biomes the
     * verge gets its snow back after shaping; the road itself stays clear.
     */
    private void settle(int x, int z, int top, boolean road) {
        int g = groundAt(x, z, top + 6);
        BlockState s = get(x, g, z);
        if (!s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) return;
        BlockState above = get(x, g + 1, z);
        boolean snow = above.is(Blocks.SNOW) || above.is(Blocks.SNOW_BLOCK);
        if (!snow && !road && above.isAir() && chunk.biome(x, g + 1, z).value().coldEnoughToSnow(pos.set(x, g + 1, z), 63)) {
            set(x, g + 1, z, Blocks.SNOW.defaultBlockState());
            snow = true;
        }
        if (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY) != snow)
            set(x, g, z, s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, snow));
    }

    private void besideShape(int x, int z, int top, float d, ChunkMask m, int i) {
        long h = hash(x, z);
        byte kind = m.kind[i];
        int s = (int) Math.floor(m.s[i]);
        Palette.Wood wood = Palette.wood(m.wood[i]);
        int ground = groundAt(x, z, top);
        if (kind == EdgePlan.GROUND && top - ground - 1 > MAX_FILL && d < 1 && m.bridge[i] == EdgePlan.STONE) kind = EdgePlan.BRIDGE;
        if (kind == EdgePlan.TUNNEL && ground <= top + 5) kind = EdgePlan.GROUND;

        if (kind == EdgePlan.BRIDGE) {
            if (d < 4.5f) bridge(x, z, top, false, d, m, i, ground);
            return;
        }
        if (kind == EdgePlan.BOARDWALK) {
            if (d < 4.5f) walkway(x, z, top, d, m, i);
            return;
        }
        if (kind == EdgePlan.BRIDGE) return;
        if (kind == EdgePlan.TUNNEL) {
            if (d >= 1.5f) return;
            boolean frame = Math.floorMod(s, 6) == 0;
            for (int y = top; y <= top + 4; y++) {
                if (m.runPos[i] <= 1 && ground > top + 6) set(x, y, z, Palette.masonry(h + y));
                else if (frame && y > top) set(x, y, z, wood.log().defaultBlockState());
                else if (!PaintRules.solid(get(x, y, z))) set(x, y, z, Blocks.STONE.defaultBlockState());
            }
            return;
        }

        BlockState natural = PaintRules.surfaceOf(get(x, ground, z));
        shoulder(x, z, top, d, ground, natural, h, m.y[i]);
        wallOrFence(x, z, top, d, m, i);
        verge(x, z, top, d);
    }

    /**
     * Plants on the verge: short grass most, then tall grass, the biome's flowers and the odd bush, in loose clumps (a
     * smooth density field about 6 blocks across), only on bare soil with room above (never on walls, fences, hedges).
     */
    private void verge(int x, int z, int top, float d) {
        if (d < 0.6f || d >= VERGE_REACH) return;
        if (propCols != null && propCols[ChunkMask.idx(x & 15, z & 15)]) return;
        long h = hash(x * 31 + 7, z * 17 - 3);
        if (d < Palette.WILD_REACH) {
            Palette.Wild wild = Palette.wild(chunk.biome(x, top + 1, z));
            if (wild != null) {
                wildVerge(x, z, top, d, wild, h);
                return;
            }
        }
        int chance = (d < 1.5f ? 230 : 330) * clump(x, z) / 512;
        if ((int) (h & 1023) >= chance) return;
        int g = groundAt(x, z, top + 6);
        if (!get(x, g + 1, z).isAir()) return;
        // dry country: a quarter as many (dead bushes and dry grass lined the desert roads like weeds)
        if (Palette.dryVerge(chunk.biome(x, g + 1, z)) && ((h >>> 52) & 3) != 0) return;
        BlockState plant = Palette.vergePlant(chunk.biome(x, g + 1, z), (int) ((h >>> 20) & 1023));
        if (plant == null || !Palette.plantable(plant, get(x, g, z))) return;
        BlockState upper = Palette.upperHalf(plant);
        if (upper != null && !get(x, g + 2, z).isAir()) {
            plant = Blocks.SHORT_GRASS.defaultBlockState();
            upper = null;
        }
        set(x, g + 1, z, plant);
        if (upper != null) set(x, g + 2, z, upper);
    }

    /**
     * The first three rows out from the road edge in a flowery biome: the biome's mix, dense and in drifts (each 3x3-block
     * cell has a lead plant, used 60% of the time). Taller plants only on the back two rows, sweet berries only on the last.
     */
    private void wildVerge(int x, int z, int top, float d, Palette.Wild wild, long h) {
        if ((int) (h & 1023) >= (int) (1024 * wild.density() * Palette.wildRow(d))) return;
        int g = groundAt(x, z, top + 6);
        // the verge is usually under vanilla grass already (plains, swamp): the flowers take its place
        if (!replaceablePlant(get(x, g + 1, z))) return;
        long cell = hash(Math.floorDiv(x, 3) * 131 + 17, Math.floorDiv(z, 3) * 89 - 5);
        BlockState plant = null;
        if (d >= 1.6f && ((h >>> 40) & 1023) < 123) plant = Palette.wildTall(wild, h);
        if (plant == null) plant = Palette.wildPlant(wild, cell, h, d >= 2.6f);
        if (!Palette.wildPlantable(plant, get(x, g, z))) return;
        BlockState upper = Palette.upperHalf(plant);
        if (upper != null && !replaceablePlant(get(x, g + 2, z))) return;
        place(x, g + 1, z, plant);
        if (upper != null) set(x, g + 2, z, upper);
    }

    /** Air, or a plant that gives way (grass, either half of tall grass), not snow or water. Flowers are not replaceable. */
    private static boolean replaceablePlant(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() && !s.is(Blocks.SNOW);
    }

    /** Smooth 0..1024 density, bilinear over 6-block cells: plants come in patches, not evenly sprinkled. */
    public static int clump(int x, int z) {
        int cx = Math.floorDiv(x, 6), cz = Math.floorDiv(z, 6);
        float tx = (x - cx * 6) / 6f, tz = (z - cz * 6) / 6f;
        float a = (hash(cx, cz) & 1023) * (1 - tx) + (hash(cx + 1, cz) & 1023) * tx;
        float b = (hash(cx, cz + 1) & 1023) * (1 - tx) + (hash(cx + 1, cz + 1) & 1023) * tx;
        return (int) (a * (1 - tz) + b * tz);
    }

    private void shoulder(int x, int z, int top, float d, int ground, BlockState natural, long h, float roadY) {
        // a tree standing here (or a branch or its canopy reaching low over it) keeps its ground: cutting under it would
        // leave it hanging, trimming its leaves would leave a loose clump of them
        if (tree(x, Math.min(top, ground) + 1, Math.max(top + 3, ground + 2), z)) return;
        if (ground == top + 1 && d < 1.5f) {
            // flush shoulder: no one-block lip along the road
            clear(x, top + 1, top + 3, z);
            set(x, top, z, natural);
            support(x, top - 1, z);
        } else if (ground > top + 1) {
            cutSlope(x, z, top, d, ground, roadY);
        } else if (ground == top - 1 && d < 1.5f) {
            set(x, top, z, natural);
            set(x, top - 1, z, Blocks.DIRT.defaultBlockState());
            clear(x, top + 1, top + 2, z);
        } else if (ground < top - 1 && top - ground <= MAX_FILL + 1) {
            // fill: 1:1 embankment down from the road edge
            int bank = Math.min(top, Math.round(roadY - Math.max(0f, d - 0.5f)));
            for (int y = ground + 1; y < bank; y++) set(x, y, z, Blocks.DIRT.defaultBlockState());
            if (bank > ground) {
                set(x, bank, z, natural);
                clear(x, bank + 1, bank + 2, z);
            }
        } else if (ground < top - 1 && !get(x, ground + 1, z).getFluidState().isEmpty()) {
            // a road built up across deeper water (a narrow channel): its sides slope into the water, two down per block,
            // instead of standing as a sheer face over the bed
            int bank = Math.min(top, Math.round(roadY - 2 * Math.max(0f, d - 0.5f)));
            BlockState bed = underwaterFill(get(x, ground, z));
            boolean dryTop = bank > ground && get(x, bank + 1, z).getFluidState().isEmpty();
            for (int y = ground + 1; y <= bank; y++) {
                boolean wet = !get(x, y, z).getFluidState().isEmpty();
                set(x, y, z, y == bank && dryTop ? natural : wet ? bed : Blocks.DIRT.defaultBlockState());
            }
            if (dryTop) clear(x, bank + 1, bank + 2, z);
        }
        feather(x, z, top, d, h);
    }

    /**
     * A cut's slope up from the road edge: about 1 block per column in soil, 2 in rock (rock stands steeper), measured from
     * the road's smooth height, with a smooth wobble of up to CUT_WOBBLE so the steps do not run in long straight lines.
     * It goes out to CUT_REACH or until it meets the hillside. Rock keeps its own stone on top (soil gets grass), and
     * the foot of a rock cut collects a little scree.
     */
    private void cutSlope(int x, int z, int top, float d, int ground, float roadY) {
        if (tree(x, Math.min(top, ground) + 1, Math.max(top + 3, ground + 2), z)) return;
        BlockState at = get(x, ground, z);
        boolean rock = rock(at);
        float rise = rock ? CUT_ROCK : CUT_SOIL;
        float wobble = (clump(x * 3 + 11, z * 3 - 5) / 1024f - 0.5f) * 2 * CUT_WOBBLE;
        int bank = Math.max(top, Math.round(roadY + rise * Math.max(0f, d - 0.5f) + wobble));
        if (ground <= bank) return;
        clear(x, bank + 1, ground + 2, z);
        BlockState face = rock ? at : PaintRules.surfaceOf(at);
        long h = hash(x * 7 - 1, z * 13 + 5);
        if (rock && d < 2.5f && bank <= top + 1 && (h & 7) == 0) face = ((h >>> 3) & 1) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
        set(x, bank, z, face);
        support(x, bank - 1, z);
    }

    static boolean rock(BlockState s) {
        return s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.TUFF)
            || s.is(Blocks.DEEPSLATE) || s.is(Blocks.CALCITE) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.TERRACOTTA)
            || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.PACKED_ICE);
    }

    /** The road frays into the verge: flush columns just outside the edge sometimes take an edge block. */
    private void feather(int x, int z, int top, float d, long h) {
        if (d >= 1.6f) return;
        BlockState here = get(x, top, z);
        if (!PaintRules.terrain(here) || PaintRules.solid(get(x, top + 1, z)) || tree(x, top + 1, top + 2, z)) return;
        int r = (int) ((h >>> 33) & 1023);
        if (r >= (int) (400 * (1 - d / 1.6f))) return;
        Palette.Surface pal = pal(x, top, z);
        BlockState edge = pal.pick(pal.edge(), pal.edgeW(), (int) ((h >>> 43) & 1023));
        clear(x, top + 1, top + 2, z);
        set(x, top, z, edge);
    }

    /** A new bank top never hangs over a cave or a cliff lip: dirt down to the next solid block (a few at most). */
    private void support(int x, int y, int z) {
        for (int k = y; k > y - 8 && !PaintRules.solid(get(x, k, z)); k--) set(x, k, z, Blocks.DIRT.defaultBlockState());
    }

    /** A tree trunk, branch or huge mushroom in this stretch of the column. */
    private boolean woody(int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) if (io.github.profetgit.countryroads.gen.GenContext.woody(get(x, y, z))) return true;
        return false;
    }

    /** A tree trunk, branch, natural leaves or huge mushroom in this stretch of the column. */
    private boolean tree(int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            BlockState s = get(x, y, z);
            if (io.github.profetgit.countryroads.gen.GenContext.woody(s) || s.is(BlockTags.LEAVES) && s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }

    private Palette.Surface pal(int x, int y, int z) {
        return Palette.surface(chunk.biome(x, y, z));
    }

    private void fillBelow(int x, int z, int ground, int top) {
        BlockState bed = underwaterFill(get(x, ground, z));
        for (int y = ground + 1; y < top; y++) {
            // under water the fill is the bed's own soil, never a rubble wall standing in the lake
            boolean wet = !get(x, y, z).getFluidState().isEmpty();
            set(x, y, z, top - y <= 3 ? Blocks.DIRT.defaultBlockState() : wet ? bed : Palette.rubble(hash(x, z) + y));
        }
    }

    /** Fill for a road built up out of water: the bed's sand, gravel, clay or mud, else dirt. */
    private static BlockState underwaterFill(BlockState bed) {
        if (bed.is(Blocks.SAND) || bed.is(Blocks.RED_SAND) || bed.is(Blocks.GRAVEL) || bed.is(Blocks.CLAY) || bed.is(Blocks.MUD)) return bed.getBlock().defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    /** A post from y down to the ground or riverbed. */
    private void post(int x, int z, int y, BlockState s) {
        BlockState post = s.hasProperty(RotatedPillarBlock.AXIS) ? s : s;
        for (int k = y; k > chunk.minY() + 1 && k > y - 64; k--) {
            BlockState here = get(x, k, z);
            if (PaintRules.solid(here) && here.getFluidState().isEmpty()) break;
            set(x, k, z, post);
        }
    }

    /** Top block of the terrain itself (solid, not a tree), searched near the planned surface. */
    private int groundAt(int x, int z, int top) {
        int hi = Math.min(chunk.maxY() - 1, top + 24), lo = Math.max(chunk.minY(), top - 48);
        for (int y = hi; y >= lo; y--) if (PaintRules.terrain(get(x, y, z))) return y;
        return lo;
    }

    static long hash(int x, int z) {
        long h = x * 0x2545F4914F6CDD1DL ^ z * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }
}
