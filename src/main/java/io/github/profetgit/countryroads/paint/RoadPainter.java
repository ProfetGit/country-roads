package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.compat.StreamsReflowing;

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
    /** Wheat field columns of this chunk (null without fields): no verge plants or bushes grow there. */
    private FieldPainter.Cols fieldCols;

    private boolean fieldCol(int x, int z) {
        return fieldCols != null && inChunk(x, z) && fieldCols.field()[ChunkMask.idx(x & 15, z & 15)] != null;
    }

    private RoadPainter(Canvas chunk) {
        this.chunk = chunk;
    }

    /** Worldgen: paints a generating chunk and records its road columns (empty too: "this chunk was handled"). */
    public static int paint(ChunkAccess chunk, ChunkMask m, java.util.List<io.github.profetgit.countryroads.world.StreetBands> streets,
                            java.util.function.IntBinaryOperator neighbourTop, Neighbours neighbours) {
        RoadColumns cols = RoadColumns.empty();
        if (NO_PAINT) {
            RoadColumns.store(chunk, cols);
            return 0;
        }
        Canvas canvas = Canvas.proto(chunk);
        int painted = m.any() ? paint(canvas, m, cols, neighbourTop, neighbours) : 0;
        painted += StreetPainter.paint(canvas, streets, chunk.getPos().x(), chunk.getPos().z(), cols);
        RoadColumns.store(chunk, cols);
        return painted;
    }

    public static int paint(Canvas canvas, ChunkMask m, RoadColumns cols) {
        return paint(canvas, m, cols, null, null);
    }

    /** NO_TOP from a neighbourTop function: that column of the next chunk is not a painted connector (or not painted yet). */
    public static final int NO_TOP = Integer.MIN_VALUE;

    /**
     * neighbourTop(x, z): the surface of an already painted connector column in a neighbouring chunk, so the later of two
     * chunks can meet it (a connector laid on the ground can meet a 2-block cliff exactly on a chunk border).
     */
    public static int paint(Canvas canvas, ChunkMask m, RoadColumns cols, java.util.function.IntBinaryOperator neighbourTop, Neighbours neighbours) {
        if (!m.any()) return 0;
        RoadPainter p = new RoadPainter(canvas);
        p.neighbours = neighbours;
        p.chunkX = m.cx;
        p.chunkZ = m.cz;
        p.neighbourTop = neighbourTop;
        p.propCols = PropPainter.footprint(m);
        p.fieldCols = FieldPainter.columns(m, p.propCols);
        int painted = 0;
        GenContext.bypass(true);
        try {
            int[] ctop = new int[256];
            java.util.Arrays.fill(ctop, Integer.MIN_VALUE);
            if (StreamsReflowing.ON) p.streamCrossings(m);
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
                            // in front of a door: the door's floor; under a porch roof or eave the column is left alone
                            if (m.doorY[i] != Integer.MIN_VALUE) {
                                if (p.groundAt(x, z, top) > m.doorY[i] + 1) continue;
                                top = m.doorY[i];
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
            if (StreamsReflowing.ON && !p.crossings.isEmpty()) p.walkLumps(m);
            // bushes and field stones last, once every column of the chunk is shaped (they reach a neighbour column)
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int i = ChunkMask.idx(lx, lz);
                    if (m.in[i] >= 0 || m.in[i] == Float.NEGATIVE_INFINITY) continue;
                    p.scatter((m.cx << 4) + lx, (m.cz << 4) + lz, (int) Math.floor(m.surface(i)), -m.in[i], m, i);
                }
            FieldPainter.paint(canvas, m, p.fieldCols, p.propCols);
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
        if (StreamsReflowing.ON && (streamBlocked(m, x, z, top) || wetPost(x, z, top))) {
            // planned on a stream another mod carved (or its bridge's rail): the post steps up to 6 along the road to the nearest dry bank in this chunk
            Direction side = d.toRoad().getClockWise();
            boolean moved = false;
            // first a spot whose arm is clear of a bridge rail, else one whose arm reaches over it (hung a block higher)
            for (int k = 1; k <= 24 && !moved; k++) {
                int step = ((k - 1) % 12 + 2) / 2 * (k % 2 == 1 ? 1 : -1);
                int nx = x + side.getStepX() * step, nz = z + side.getStepZ() * step;
                int ax = nx + d.toRoad().getStepX(), az = nz + d.toRoad().getStepZ();
                if (!inChunk(nx, nz) || !inChunk(ax, az) || m.in[ChunkMask.idx(nx & 15, nz & 15)] >= 0) continue;
                if (streamBlocked(m, nx, nz, top) || wetPost(nx, nz, top) || k <= 12 && streamBlocked(m, ax, az, top)) continue;
                x = nx;
                z = nz;
                moved = true;
            }
            // else a step or two away from the road, behind the bridge's rail (a junction post on a crossing's landing)
            for (int o = 1; o <= 2 && !moved; o++) {
                int nx = d.x() - d.toRoad().getStepX() * o, nz = d.z() - d.toRoad().getStepZ() * o;
                int ax = nx + d.toRoad().getStepX(), az = nz + d.toRoad().getStepZ();
                if (!inChunk(nx, nz) || !inChunk(ax, az) || m.in[ChunkMask.idx(nx & 15, nz & 15)] >= 0 || streamBlocked(m, nx, nz, top) || wetPost(nx, nz, top)
                    || streamBlocked(m, ax, az, top) && m.in[ChunkMask.idx(ax & 15, az & 15)] < 0) continue;
                x = nx;
                z = nz;
                moved = true;
            }
            int standOn = Integer.MIN_VALUE;
            if (!moved) {
                // nowhere dry within reach (a stream running along the road): the post stands in the stream on a log
                // driven into its bed, like a bridge post
                int wy = StreamsReflowing.anyWater(chunk, streamPos, d.x(), d.z(), top - 12, top + 3), bed = wy;
                while (wy != StreamsReflowing.NONE && bed > top - 8 && !get(d.x(), bed, d.z()).getFluidState().isEmpty()) bed--;
                if (wy == StreamsReflowing.NONE || wy > top || !PaintRules.solid(get(d.x(), bed, d.z())) || !get(d.x(), bed, d.z()).getFluidState().isEmpty()) return;
                Palette.Wood pw = Palette.wood(m.wood[i]);
                for (int y = bed + 1; y <= top; y++) set(d.x(), y, d.z(), pw.log().defaultBlockState());
                standOn = top;
            }
            if (standOn != Integer.MIN_VALUE) {
                gallows(d, m, i, x, z, standOn, top);
                return;
            }
        }
        int g = groundAt(x, z, top);
        if (Math.abs(g - top) > 4 || !PaintRules.terrain(get(x, g, z)) || woody(x, g + 1, g + 5, z)) return;
        gallows(d, m, i, x, z, g, top);
    }

    /**
     * With Streams Reflowing, a post spot over water too deep for a post to stand in (its ground more than 4 under the
     * road): a river the noise column knows, which that mod carved deeper than vanilla, so it is no stream of its own
     * (streamWater) and the post was dropped.
     */
    private boolean wetPost(int x, int z, int top) {
        int g = groundAt(x, z, top);
        return top - g > 4 && !get(x, g + 1, z).getFluidState().isEmpty();
    }

    /** The gallows post of a signpost or lamp standing on y = g (its foot one above). */
    private void gallows(EdgePlan.Decor d, ChunkMask m, int i, int x, int z, int g, int top) {
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
        int up = 0;
        if (StreamsReflowing.ON && !((arm.isAir() || arm.canBeReplaced()) && (under.isAir() || under.canBeReplaced()))
            && (get(ax, g + 5, az).isAir() || get(ax, g + 5, az).canBeReplaced()) && (get(ax, g + 4, az).isAir() || get(ax, g + 4, az).canBeReplaced())
            && (get(x, g + 5, z).isAir() || get(x, g + 5, z).canBeReplaced())) {
            // the arm reaches over a stream bridge's rail fence: the post goes a block higher and the sign hangs above the rail
            up = 1;
            exact(x, g + 4, z, fence);
            exact(x, g + 5, z, fence.setValue(net.minecraft.world.level.block.CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(toRoad), true));
            arm = get(ax, g + 5, az);
            under = get(ax, g + 4, az);
        }
        if ((arm.isAir() || arm.canBeReplaced()) && (under.isAir() || under.canBeReplaced())) {
            exact(ax, g + 4 + up, az, fence.setValue(net.minecraft.world.level.block.CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(toRoad.getOpposite()), true));
            if (d.type() == EdgePlan.SIGN) hangingSign(wood, ax, g + 3 + up, az, d);
            else lantern(ax, g + 3 + up, az, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), true);
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
    /** Share of the wildflower verge's density that is planted (user 2026-10-16: 75% fewer flowers). */
    static final float WILD_SHARE = 0.25f;

    /**
     * Roadside bushes and field stones, 2.2-4.6 blocks out from the road edge on level soil: a bush is one to four leaf
     * blocks of the biome's hedge leaves (persistent), now and then two high; a field stone is a stone or andesite block
     * with a slab of the same stone beside it. Bushes come in patches (smooth noise), stones are rare. Everything stays
     * inside this chunk, so neighbouring chunks never disagree, and is placed after the chunk's shaping is done.
     */
    private void scatter(int x, int z, int top, float d, ChunkMask m, int i) {
        if (d < 2.2f || d > 4.6f || m.kind[i] != EdgePlan.GROUND || m.conn[i] || propCols != null && propCols[i] || fieldCol(x, z)) return;
        long h = hash(x * 13 + 5, z * 7 - 11);
        int r = (int) (h & 1023), patch = clump(x + 31, z - 17);
        // bushes at a quarter of the old rate (user 2026-10-16: 75% fewer flowers and bushes beside roads)
        boolean stone = r < STONE, bush = !stone && r < STONE + BUSH * patch * patch / (1024 * 1024 * 2);
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
        if (propCols != null && inChunk(x, z) && propCols[ChunkMask.idx(x & 15, z & 15)] || fieldCol(x, z)) return false;
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
        //? if >=26.3 {
        return new SignText(lines, lines, DyeColor.BLACK, false);
        //?} else {
        /*Component[] arr = lines.toArray(new Component[0]);
        return new SignText(arr, arr, DyeColor.BLACK, false);
        *///?}
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
        // a bamboo or cactus stalk cut from below goes whole (the rest would float)
        for (int y = y1 + 1; y < chunk.maxY() && PaintRules.stalk(get(x, y, z)); y++) set(x, y, z, AIR);
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
        boolean dbg = io.github.profetgit.countryroads.gen.GenContext.debug(x, z);
        if (dbg) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE before " + x + "," + z + " top=" + top + " ground=" + groundAt(x, z, top) + dump(x, z, top));
        if (dbg) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE stream " + x + "," + z + " kind=" + m.kind[i] + " wy=" + streamWater(x, z, top) + " " + StreamsReflowing.describe(chunk, x, z));
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
                else if (Math.floorMod(s, 12) == 6 && in > 0.8f) lantern(x, top + 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), false);
                if (!PaintRules.solid(get(x, top + 5, z))) set(x, top + 5, z, Blocks.STONE.defaultBlockState());
                if (m.runPos[i] <= 1 && ground > top + 7) set(x, top + 5, z, Palette.masonry(h));
            }
            default -> {
                int wy = m.kind[i] == EdgePlan.GROUND ? streamWater(x, z, top) : StreamsReflowing.NONE;
                float[] cr = crossing(m, i);
                if (m.kind[i] == EdgePlan.GROUND && !crossings.isEmpty()) {
                    float[] sq = squareAt(x, z);
                    if (sq != null) {
                        squareBridge(x, z, top, m, i, sq, wy);
                        return;
                    }
                }
                if (cr != null && cr[3] >= 0) {
                    // a squared crossing's rows beside its deck: the stream stays open, dry ground is verge (the road
                    // ran on beside the rail a block below the deck; it reaches the deck over the landing on the axis)
                    if (wy != StreamsReflowing.NONE && wy <= top) return;
                    int al = cr[3] == 0 ? x : z;
                    if (al < cr[4] || al > cr[5]) {
                        squareLanding(x, z, m, i, cr, StreamsReflowing.NONE, al);
                        return;
                    }
                    BlockState natural = get(x, ground, z);
                    if (ground > top) clear(x, top + 1, ground + 2, z);
                    else fillBelow(x, z, ground, top);
                    if (!PaintRules.solid(get(x, top - 1, z))) set(x, top - 1, z, Blocks.DIRT.defaultBlockState());
                    set(x, top, z, PaintRules.surfaceOf(natural));
                    clear(x, top + 1, top + 3, z);
                    return;
                } else {
                    if (cr == null && wy != StreamsReflowing.NONE && wy < top) cr = new float[]{m.s[i] - STREAM_LANDING, m.s[i] + STREAM_LANDING, 0, -1, 0, 0, 0, 0, 0};
                    if (cr != null && ((int) cr[2] & 4) != 0) {
                        // the boardwalk's pieces lie on the water like the boardwalk's own, one layer at one height: a block
                        // over the crossing's highest water (on a ford row a block over the road, the stream running on
                        // under them; at the road's height they were drowned in its water)
                        int lv = walkLevel(cr, top, wy);
                        if (wy != StreamsReflowing.NONE && wy < lv) {
                            walkway(x, z, lv, -in, m, i);
                            return;
                        }
                        if (wy == StreamsReflowing.NONE) {
                            // a dry row: the walkway runs on at its height over the ground, filled up or cut down to it (a
                            // piece on a road block stood up as a lump, and a road block between pieces read as one); toward
                            // a bank the road climbs from it half a block per row to its own height (a block's step onto
                            // the bank, critic 2026-11-18)
                            double feet = walkFeet(cr, lv, m.s[i], Math.max(0.5f, m.ds[i]), m.surface(i) + 1);
                            BlockState natural = get(x, ground, z);
                            // (a road no higher than the walkway's layer just carries its pieces: a ramp block there stood up
                            // between the walkway and a boardwalk)
                            if (feet <= lv + 0.5 || m.surface(i) < lv + 0.5) {
                                if (ground >= lv) {
                                    clear(x, lv, ground + 2, z);
                                    set(x, lv - 1, z, PaintRules.surfaceOf(natural));
                                } else fillBelow(x, z, ground, lv);
                                walkway(x, z, lv, -in, m, i);
                                return;
                            }
                            int t = (int) Math.floor(feet - 1);
                            if (ground > t) clear(x, t + 1, ground + 2, z);
                            else fillBelow(x, z, ground, t);
                            if (!PaintRules.solid(get(x, t - 1, z))) set(x, t - 1, z, Blocks.DIRT.defaultBlockState());
                            Palette.Surface wp = pal(x, t, z);
                            set(x, t, z, Palette.surface(wp, in, h, natural, ground == t));
                            clear(x, t + 1, t + 3, z);
                            if (feet - 1 - t >= 0.5) set(x, t + 1, z, wp.step().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                            return;
                        }
                        cr = null;
                    }
                    if (cr != null) {
                        streamBridge(x, z, top, half, deckD(m, i), m, i, ground, cr, wy);
                        return;
                    }
                }
                if (wy == top && PaintRules.solid(get(x, top - 1, z)) && get(x, top - 1, z).getFluidState().isEmpty()) {
                    ford(x, z, top, ground);
                    return;
                }
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

    private final BlockPos.MutableBlockPos streamPos = new BlockPos.MutableBlockPos();

    /** Y of stream water (Streams Reflowing) in this column, at most 3 over the road surface, else NONE. */
    private int streamWater(int x, int z, int top) {
        return StreamsReflowing.ON ? StreamsReflowing.water(chunk, streamPos, x, z, top - 12, top + 3) : StreamsReflowing.NONE;
    }

    /**
     * A ground road over a stream that was carved after the plan was made (the planner reads the noise column, which has
     * no such stream) crosses it on the road's own timber bridge at its planned height, the stream running on under it
     * (see streamCrossings). Outside a crossing, a column whose water is at the road block over a bed one deep is a ford:
     * the water stays on the road (decking it would dam the stream).
     */
    private void ford(int x, int z, int top, int ground) {
        clear(x, top + 1, Math.max(top + 3, ground + 2), z);
    }

    /** The plank laid over water that reaches under a finished road later (GenContext.guardWrite): the bridge deck's planks. */
    public static BlockState streamDeck(ChunkMask m, int i) {
        return Palette.bridgeWood(streamPair(m, i), false).planks();
    }

    /** The bridge wood pair of a stream crossing: the road's deck wood, like a trestle the painter adds. */
    private static int streamPair(ChunkMask m, int i) {
        return new int[]{2, 0, 6, 3, 4}[Math.clamp(m.wood[i], 0, 4)];
    }

    /** Neighbouring chunks a generating chunk can read: a chunk is returned once it is painted, else null. */
    public interface Neighbours {
        ChunkAccess painted(int cx, int cz);
    }

    private Neighbours neighbours;

    /** Stream crossings of this chunk per plan: {s0, s1} in samples, an end the next chunk finishes is infinite. */
    private final java.util.Map<EdgePlan, java.util.List<float[]>> crossings = new java.util.IdentityHashMap<>();

    private static final int SIDE_CLOSED = 0, SIDE_OPEN = 1, SIDE_DECK = 2, SIDE_PLAIN = 3, SIDE_WALK = 4;

    /**
     * Collected before anything is painted. A crossing spans the road's whole width, square across it, from 1.5 blocks
     * before the first row whose road or first verge column has stream water under the road surface to 1.5 after the
     * last; rows with water at the road block (fords) and dry stretches up to STREAM_GAP join two such rows into one
     * crossing. Where the road runs on into the next chunk, that chunk's water is often not there yet: a crossing near
     * that border stays open toward it, and the next chunk, painted later, finds the deck at the border and finishes it
     * (else a crossing on a chunk border ended at the border with a dirt island before the next deck).
     */
    private void streamCrossings(ChunkMask m) {
        final boolean TRACE_CROSSINGS = Boolean.getBoolean("countryroads.traceCrossings") || !DEBUG.isEmpty() && java.util.Arrays.stream(DEBUG.split(";")).anyMatch(xz -> {
            String[] a = xz.split(",");
            return a.length == 2 && (Integer.parseInt(a[0].trim()) >> 4) == m.cx && (Integer.parseInt(a[1].trim()) >> 4) == m.cz;
        });
        java.util.Map<EdgePlan, java.util.List<float[]>> pts = new java.util.IdentityHashMap<>();
        java.util.Map<EdgePlan, float[]> range = new java.util.IdentityHashMap<>();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int i = ChunkMask.idx(lx, lz);
                EdgePlan p = m.plan[i];
                if (p == null || !(m.in[i] > -1.5f) || m.kind[i] != EdgePlan.GROUND) continue;
                float[] r = range.computeIfAbsent(p, k -> new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, 0.5f});
                r[0] = Math.min(r[0], m.s[i]);
                r[1] = Math.max(r[1], m.s[i]);
                r[2] = Math.max(r[2], m.ds[i]);
                int top = (int) Math.floor(m.surface(i));
                int wy = streamWater((m.cx << 4) + lx, (m.cz << 4) + lz, top);
                if (wy == StreamsReflowing.NONE || wy > top) continue;
                pts.computeIfAbsent(p, k -> new java.util.ArrayList<>()).add(new float[]{m.s[i], wy < top ? 1 : 0, wy});
            }
        for (var e : range.entrySet()) {
            EdgePlan p = e.getKey();
            float lo = e.getValue()[0], hi = e.getValue()[1], per = e.getValue()[2];
            java.util.List<float[]> list = pts.getOrDefault(p, new java.util.ArrayList<>());
            int loSide = SIDE_CLOSED, hiSide = SIDE_CLOSED;
            if (!list.isEmpty() || neighbours != null) {
                loSide = side(p, lo, -1, m);
                hiSide = side(p, hi, 1, m);
            }
            if (loSide == SIDE_DECK || loSide == SIDE_WALK) list.add(new float[]{lo - 0.5f, 2});
            if (hiSide == SIDE_DECK || hiSide == SIDE_WALK) list.add(new float[]{hi + 0.5f, 3});
            if (list.isEmpty()) continue;
            list.sort((a, b) -> Float.compare(a[0], b[0]));
            java.util.List<float[]> out = new java.util.ArrayList<>();
            float gap = STREAM_GAP * per, land = STREAM_LANDING * per;
            int from = 0;
            for (int k = 1; k <= list.size(); k++) {
                if (k < list.size() && list.get(k)[0] - list.get(k - 1)[0] <= gap) continue;
                // one cluster of wet rows: a crossing if any row has water under the road surface
                float d0 = Float.NaN, d1 = Float.NaN;
                boolean openLo = false, openHi = false;
                // a walkway over these rows lies a block over their highest water, like a planned boardwalk
                int level = Integer.MIN_VALUE;
                for (int q = from; q < k; q++) if (list.get(q).length > 2) level = Math.max(level, (int) list.get(q)[2] + 1);
                for (int q = from; q < k; q++) {
                    float[] pt = list.get(q);
                    if (pt[1] == 0) continue;
                    if (Float.isNaN(d0)) d0 = pt[0];
                    d1 = pt[0];
                    openLo |= pt[1] == 2;
                    openHi |= pt[1] == 3;
                }
                float w0 = list.get(from)[0], w1 = list.get(k - 1)[0];
                from = k;
                // only fords: a creek of a row or two keeps its water on the road; a longer stretch of the road under the
                // stream's water (a swamp stream running along it) gets the boardwalk's walkway laid on the water
                boolean walkFord = Float.isNaN(d0) && (w1 - w0 >= STREAM_FORD_MAX * per || nearKind(p, w0, w1, lo, hi, EdgePlan.BOARDWALK, STREAM_NEAR_WALK));
                if (walkFord) {
                    d0 = w0;
                    d1 = w1;
                }
                if (Float.isNaN(d0)) continue;
                // ford rows running on from a deck to one of the road's boardwalks are walkway: the boardwalk's pieces from
                // the deck's end to the boardwalk (as fords they left road blocks scattered in the water beside it)
                if (!walkFord && w1 - d1 > land && nearKind(p, w1, w1, lo, hi, EdgePlan.BOARDWALK, STREAM_NEAR_WALK))
                    out.add(new float[]{d1 + land + 0.25f, Math.min(w1 + land, hi + 0.25f), 4, -1, 0, 0, 0, 0, 2, level, d1 + land, w1, SQUARE_LANDING, SQUARE_LANDING});
                if (!walkFord && d0 - w0 > land && nearKind(p, w0, w0, lo, hi, EdgePlan.BOARDWALK, STREAM_NEAR_WALK))
                    out.add(new float[]{Math.max(w0 - land, lo - 0.25f), d0 - land - 0.25f, 4, -1, 0, 0, 0, 0, 2, level, w0, d0 - land, SQUARE_LANDING, SQUARE_LANDING});
                // the style of the painted neighbour this crossing runs on from: 1 its timber deck, 2 its walkway
                int inherit = openLo ? (loSide == SIDE_WALK ? 2 : 1) : openHi ? (hiSide == SIDE_WALK ? 2 : 1) : 0;
                float s0 = d0 - land, s1 = d1 + land;
                if (openLo || loSide == SIDE_OPEN && w0 - lo <= gap) s0 = Float.NEGATIVE_INFINITY;
                else if (loSide != SIDE_OPEN) s0 = Math.max(s0, lo - 0.25f);
                if (openHi || hiSide == SIDE_OPEN && hi - w1 <= gap) s1 = Float.POSITIVE_INFINITY;
                else if (hiSide != SIDE_OPEN) s1 = Math.min(s1, hi + 0.25f);
                out.add(new float[]{s0, s1, walkFord ? 4 : 0, -1, 0, 0, 0, 0, walkFord ? 2 : inherit, level, d0, d1, SQUARE_LANDING, SQUARE_LANDING});
            }
            out.sort((a, b) -> Float.compare(a[0], b[0]));
            // crossings whose landings nearly touch are one bridge (two end posts stood back to back between them)
            for (int k = out.size() - 1; k > 0; k--)
                if (out.get(k)[0] - out.get(k - 1)[1] <= STREAM_JOIN * per && ((int) out.get(k)[2] & 4) == ((int) out.get(k - 1)[2] & 4)) {
                    out.get(k - 1)[1] = out.get(k)[1];
                    out.get(k - 1)[8] = Math.max(out.get(k - 1)[8], out.get(k)[8]);
                    out.remove(k);
                }
            for (int k = 0; k < out.size(); k++) {
                float[] c = out.get(k);
                // running into one of the road's own bridges: the deck meets it without an end post of its own
                int b0 = joinsBridge(p, c[0], -1), b1 = joinsBridge(p, c[1], 1);
                if (b0 >= 0) {
                    c[0] = b0 + 0.5f;
                    c[2] = (int) c[2] | 1;
                }
                if (b1 >= 0) {
                    c[1] = b1 - 0.5f;
                    c[2] = (int) c[2] | 2;
                }
                // a landing reaching into a boardwalk or tunnel ends on the last ground row, so its end post stands there
                int n = p.length();
                if (b1 < 0 && !Float.isInfinite(c[1])) {
                    int q = Math.min(n - 1, (int) Math.floor(c[1]));
                    while (q > c[0] && p.kind[q] != EdgePlan.GROUND) q--;
                    if (q < Math.floor(c[1])) c[1] = q + 0.5f;
                }
                if (b0 < 0 && !Float.isInfinite(c[0])) {
                    int q = Math.max(0, (int) Math.ceil(c[0]));
                    while (q < c[1] && p.kind[q] != EdgePlan.GROUND) q++;
                    if (q > Math.ceil(c[0])) c[0] = q - 0.5f;
                }
                // near one of the road's boardwalks the stream is crossed with the boardwalk's own walkway pieces; a crossing
                // running on from a painted neighbour keeps that neighbour's style (else a deck met a walkway mid-stream)
                if (c[8] == 2 || c[8] == 0 && nearKind(p, c[0], c[1], lo, hi, EdgePlan.BOARDWALK, STREAM_NEAR_WALK)) c[2] = (int) c[2] | 4;
                // a walkway runs on over the ground rows to a boardwalk within reach (road blocks and their step slabs were
                // left between the two, critic 2026-11-18)
                if (((int) c[2] & 4) != 0) {
                    if (!Float.isInfinite(c[1]))
                        for (int q = (int) Math.ceil(c[1]), k2 = 0; k2 <= STREAM_NEAR_WALK && q + k2 < n; k2++) {
                            if (p.kind[q + k2] == EdgePlan.BOARDWALK) {
                                c[1] = q + k2 - 0.5f;
                                break;
                            }
                            if (p.kind[q + k2] != EdgePlan.GROUND) break;
                        }
                    if (!Float.isInfinite(c[0]))
                        for (int q = (int) Math.floor(c[0]), k2 = 0; k2 <= STREAM_NEAR_WALK && q - k2 >= 0; k2++) {
                            if (p.kind[q - k2] == EdgePlan.BOARDWALK) {
                                c[0] = q - k2 + 0.5f;
                                break;
                            }
                            if (p.kind[q - k2] != EdgePlan.GROUND) break;
                        }
                }
                square(p, c, m);
            }
            if (TRACE_CROSSINGS && !out.isEmpty()) {
                StringBuilder sb = new StringBuilder("CR-TRACE crossings chunk " + m.cx + "," + m.cz + " plan " + p.edge.a().pos().getX() + "," + p.edge.a().pos().getZ()
                    + "->" + p.edge.b().pos().getX() + "," + p.edge.b().pos().getZ() + " lo=" + lo + " hi=" + hi + " sides=" + loSide + "/" + hiSide + " pts=" + list.size());
                for (float[] c : out) sb.append(" [").append(c[0]).append("..").append(c[1]).append(" f=").append((int) c[2]).append(" sq=").append(c[3]).append(']');
                io.github.profetgit.countryroads.CountryRoads.LOG.info(sb.toString());
            }
            if (!out.isEmpty()) crossings.put(p, out);
        }
    }

    /**
     * What lies past this chunk's last row of a plan (dir -1: before lo, +1: after hi): CLOSED (the road ends, or stays in
     * this chunk), OPEN (the next chunk is not painted yet), DECK (painted, with a stream deck at the border) or PLAIN.
     */
    private int side(EdgePlan p, float s, int dir, ChunkMask m) {
        float probe = s + dir * 2f;
        int n = p.length();
        if (probe < 0 || probe > n - 1) return SIDE_CLOSED;
        // the road goes on as one of its own boardwalks, bridges or tunnels: the crossing ends here (it is not continued
        // in the next chunk, and its end must be known to meet that piece: an open end skipped the walkway rule)
        for (int q = (int) Math.floor(Math.min(s, probe)); q <= (int) Math.ceil(Math.max(s, probe)); q++)
            if (q >= 0 && q < n && p.kind[q] != EdgePlan.GROUND) return SIDE_CLOSED;
        int k = Math.min((int) Math.floor(probe), n - 2);
        float t = probe - k;
        int cx = (int) Math.floor(p.x[k] + t * (p.x[k + 1] - p.x[k])) >> 4, cz = (int) Math.floor(p.z[k] + t * (p.z[k + 1] - p.z[k])) >> 4;
        if (cx == m.cx && cz == m.cz || Math.abs(cx - m.cx) > 1 || Math.abs(cz - m.cz) > 1 || neighbours == null) return SIDE_CLOSED;
        try {
            ChunkAccess nb = neighbours.painted(cx, cz);
            if (nb == null) return SIDE_OPEN;
            ChunkMask nm = io.github.profetgit.countryroads.gen.GenContext.mask(nb.getPos());
            if (nm == null) return SIDE_PLAIN;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            for (int c = 0; c < 256; c++) {
                if (nm.plan[c] != p || !(nm.in[c] >= 0) || nm.kind[c] != EdgePlan.GROUND || Math.abs(nm.s[c] - s) > 2.5f) continue;
                int top = (int) Math.floor(nm.surface(c));
                BlockState b = nb.getBlockState(q.set((cx << 4) + (c & 15), top, (cz << 4) + (c >> 4)));
                if (b.is(BlockTags.PLANKS)) return SIDE_DECK;
                if (walkwayPiece(b)) return SIDE_WALK;
            }
            return SIDE_PLAIN;
        } catch (RuntimeException ex) {
            return SIDE_CLOSED;
        }
    }

    /** A boardwalk walkway piece (see walkway). */
    private static boolean walkwayPiece(BlockState b) {
        return b.is(Blocks.SPRUCE_SLAB) || b.is(Blocks.SPRUCE_TRAPDOOR) || b.is(Blocks.CAMPFIRE);
    }

    /** Samples a stream deck runs on over the ground to meet one of the road's own bridges (its approach eases sideways). */
    static final int STREAM_JOIN_BRIDGE = 12;

    /**
     * The nearest sample past s (dir +-1) within STREAM_JOIN_BRIDGE that is one of the road's planned bridges, else -1.
     * A boardwalk (the ragged slab walkway over still water) is never joined: the crossing stops short of it.
     */
    private static int joinsBridge(EdgePlan p, float s, int dir) {
        if (Float.isInfinite(s)) return -1;
        int n = p.length();
        for (int k = 0; k <= STREAM_JOIN_BRIDGE; k++) {
            int q = dir > 0 ? (int) Math.ceil(s) + k : (int) Math.floor(s) - k;
            if (q < 0 || q >= n || p.kind[q] == EdgePlan.BOARDWALK || p.kind[q] == EdgePlan.TUNNEL) return -1;
            if (p.kind[q] == EdgePlan.BRIDGE) return q;
        }
        return -1;
    }

    /** Samples from a boardwalk within which a stream crossing takes the boardwalk's walkway pieces. */
    static final int STREAM_NEAR_WALK = 6;

    /** Whether the plan has a sample of this kind within reach of either end of [s0, s1]. */
    private static boolean nearKind(EdgePlan p, float s0, float s1, float lo, float hi, byte kind, int reach) {
        // an open end is measured from this chunk's last row (the crossing goes on past it, so the piece is at least that
        // close), never along the whole road
        if (Float.isInfinite(s0)) s0 = lo;
        if (Float.isInfinite(s1)) s1 = hi;
        int n = p.length();
        if (!Float.isInfinite(s0))
            for (int q = Math.max(0, (int) Math.floor(s0) - reach); q <= Math.min(n - 1, (int) Math.floor(s0) + reach); q++) if (p.kind[q] == kind) return true;
        if (!Float.isInfinite(s1))
            for (int q = Math.max(0, (int) Math.ceil(s1) - reach); q <= Math.min(n - 1, (int) Math.ceil(s1) + reach); q++) if (p.kind[q] == kind) return true;
        return false;
    }

    /** x, z, y and half width of a plan at s (clamped to the plan). */
    private static double[] at(EdgePlan p, float s) {
        int n = p.length();
        float c = Math.clamp(s, 0f, n - 1f);
        int k = Math.min((int) c, n - 2);
        float t = c - k;
        return new double[]{p.x[k] + t * (p.x[k + 1] - p.x[k]), p.z[k] + t * (p.z[k + 1] - p.z[k]), p.y[k] + t * (p.y[k + 1] - p.y[k]),
            p.hw[k] + t * (p.hw[k + 1] - p.hw[k])};
    }

    /**
     * A crossing on a diagonal stretch is laid square to the nearer axis, like the road's planned bridges: a straight deck
     * as wide as the road, centred on the crossing's middle, from one end of the crossing to the other along that axis,
     * straight rails either side (on the diagonal the deck followed the road's staircase of columns and its rails zigzagged:
     * critic REJECT 2026-11-18). Only when both ends are known and the whole bridge lies in this chunk (another chunk
     * could not paint its share the same way); the road's columns in those rows beside the deck keep the stream open.
     * Fills c[3..7]: axis (0 x, 1 z), first and last column along it, middle column across it, deck half width.
     */
    private static void square(EdgePlan p, float[] c, ChunkMask m) {
        if (Float.isInfinite(c[0]) || Float.isInfinite(c[1]) || (int) c[2] != 0) return;
        double[] a = at(p, c[0]), b = at(p, c[1]), q = at(p, (c[0] + c[1]) / 2);
        double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
        if (len < 1 || Math.min(Math.abs(tx), Math.abs(tz)) / len < 0.3) return;
        boolean xAxis = Math.abs(tx) >= Math.abs(tz);
        int ia0 = (int) Math.floor(Math.min(xAxis ? a[0] : a[1], xAxis ? b[0] : b[1]));
        int ia1 = (int) Math.floor(Math.max(xAxis ? a[0] : a[1], xAxis ? b[0] : b[1]));
        int mid = (int) Math.floor(xAxis ? q[1] : q[0]);
        int half = 1;
        int alLo = xAxis ? m.cx << 4 : m.cz << 4, crLo = xAxis ? m.cz << 4 : m.cx << 4;
        if (ia0 < alLo || ia1 > alLo + 15 || mid - half - 1 < crLo || mid + half + 1 > crLo + 15) return;
        c[3] = xAxis ? 0 : 1;
        c[4] = ia0;
        c[5] = ia1;
        c[6] = mid;
        c[7] = half;
        // each landing runs on along the axis until the road's own columns cover its whole width (a diagonal road reached
        // the deck end beside a 2-block landing, with grass between them, critic 2026-11-18)
        for (int sd = 0; sd < 2; sd++) {
            int land = 0;
            for (int dd = 1; dd <= SQUARE_LANDING_MAX; dd++) {
                int a2 = sd == 0 ? ia0 - dd : ia1 + dd;
                boolean inside = true, road = true;
                for (int o = -half; o <= half; o++) {
                    int rx = xAxis ? a2 : mid + o, rz = xAxis ? mid + o : a2;
                    if (rx >> 4 != m.cx || rz >> 4 != m.cz) inside = false;
                    else if (!(m.in[ChunkMask.idx(rx & 15, rz & 15)] >= 0)) road = false;
                }
                if (!inside) break;
                land = dd;
                if (road && dd >= SQUARE_LANDING) break;
            }
            c[12 + sd] = land;
        }
    }

    /** 1: deck, 2: rail, 3: landing (road on the axis past an end) of the squared crossing c at this column, else 0. */
    private static int squareRole(float[] c, int x, int z) {
        if (c[3] < 0) return 0;
        boolean xAxis = c[3] == 0;
        int al = xAxis ? x : z, cr = xAxis ? z : x;
        int off = Math.abs(cr - (int) c[6]);
        if (al < c[4] || al > c[5]) {
            float lo = c.length > 13 ? c[12] : SQUARE_LANDING, hi = c.length > 13 ? c[13] : SQUARE_LANDING;
            return off <= c[7] && (al >= c[4] - lo && al < c[4] || al > c[5] && al <= c[5] + hi) ? 3 : 0;
        }
        return off <= c[7] ? 1 : off == c[7] + 1 ? 2 : 0;
    }

    /** Blocks of road laid on a squared crossing's axis past each end, so the road meets the deck end-on. */
    static final int SQUARE_LANDING = 2, SQUARE_LANDING_MAX = 6;

    /** A squared crossing whose end posts this column stands beside (2 out past a rail, a block either side of an end), or null. */
    private float[] nearSquareEnd(int x, int z) {
        for (java.util.List<float[]> l : crossings.values())
            for (float[] c : l) {
                if (c[3] < 0) continue;
                boolean xAxis = c[3] == 0;
                int al = xAxis ? x : z, off = Math.abs((xAxis ? z : x) - (int) c[6]);
                if (off >= c[7] + 1 && off <= c[7] + 3 && (Math.abs(al - c[4]) <= 1 || Math.abs(al - c[5]) <= 1) && squareRole(c, x, z) == 0) return c;
            }
        return null;
    }

    /** The squared crossing whose deck or rail this column is, or null. */
    private float[] squareAt(int x, int z) {
        for (java.util.List<float[]> l : crossings.values())
            for (float[] c : l) if (squareRole(c, x, z) > 0) return c;
        return null;
    }

    /** One column of a squared crossing (see square): posts spaced evenly along the axis, 2 lanterns on a span under 6. */
    private void squareBridge(int x, int z, int top, ChunkMask m, int i, float[] c, int wy) {
        // one height across each row of the deck: the highest planned surface of the row's columns (each column's own
        // height stair-stepped across the diagonal road: planks a block up beside the deck, critic 2026-11-18; never
        // lower than a column's own, so the deck never sinks into the stream)
        int al0 = c[3] == 0 ? x : z;
        if (squareRole(c, x, z) == 3) {
            squareLanding(x, z, m, i, c, wy, al0);
            return;
        }
        double yv = squareDeck(m, c, al0);
        if (yv > Double.NEGATIVE_INFINITY) top = (int) Math.floor(yv);
        boolean half = yv - top >= 0.5;
        if (wy != StreamsReflowing.NONE && wy > top) return;
        int role = squareRole(c, x, z);
        boolean rail = role == 2, deck = role == 1, xAxis = c[3] == 0;
        int pair = streamPair(m, i);
        Palette.BridgeWood main = Palette.bridgeWood(pair, false), trim = Palette.bridgeWood(pair, true);
        Direction along = xAxis ? Direction.EAST : Direction.SOUTH;
        int al = xAxis ? x : z, cr = xAxis ? z : x, n = (int) (c[5] - c[4]);
        int side = cr > c[6] ? 1 : -1;
        Direction out = xAxis ? (side > 0 ? Direction.SOUTH : Direction.NORTH) : (side > 0 ? Direction.EAST : Direction.WEST);
        int bays = Math.max(1, Math.round(n / TIMBER_BAY));
        float step = Math.max(1f, (float) n / bays), u = al - c[4];
        int j = Math.round(u / step);
        float off = u - j * step;
        boolean bent = Math.abs(off) < 0.5f, end = bent && (j == 0 || j >= bays);
        boolean lamp = end ? n + 1 >= 6 || (j == 0) == (side > 0) : Math.floorMod(j, 2) == 0;
        boolean brace = !bent && Math.abs(off) < 1.5f;
        boolean nearEnd = al == (int) c[4] || al == (int) c[5];
        int ground = groundAt(x, z, top);
        underDeck(x, z, top, ground, wy, deck);
        timberColumn(x, z, top, half && deck, rail, deck, false, true, main, trim, along, out, top - 2, ground, bent, nearEnd, brace, off > 0, lamp, true);
    }

    /**
     * A squared deck's surface at this place along its axis. One height for the whole deck (the highest planned surface
     * of its columns: a step a block up mid-deck broke both rails, critic 2026-11-18) when its rows differ by a block at
     * most; on a steeper crossing row by row (each row's highest), a half step before a row a block higher (one height
     * there left the low end's landing two blocks over the road). Never lower than a column's own: the deck never sinks
     * into the stream.
     */
    private double squareDeck(ChunkMask m, float[] c, int al) {
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int a = (int) c[4]; a <= (int) c[5]; a++) {
            double r = squareRow(m, c, a);
            if (r == Double.NEGATIVE_INFINITY) continue;
            hi = Math.max(hi, r);
            lo = Math.min(lo, r);
        }
        if (hi == Double.NEGATIVE_INFINITY || hi - lo <= 1.0) return hi;
        double here = squareRow(m, c, al);
        if (here == Double.NEGATIVE_INFINITY) return here;
        int t = (int) Math.floor(here);
        if (here - t < 0.5 && Math.floor(Math.max(squareRow(m, c, al - 1), squareRow(m, c, al + 1))) > t) here = t + 0.5;
        return here;
    }

    /**
     * Road past a squared deck's end: the landing on its axis, and the road's own columns there (they and the landing
     * must read as one road onto the deck: grass between them parted road and bridge, critic 2026-11-18). At the deck's
     * height next to it, then half a block down per block to the column's own; over the stream the deck's planks.
     */
    private void squareLanding(int x, int z, ChunkMask m, int i, float[] c, int wy, int al) {
        double yv = squareDeck(m, c, al < c[4] ? (int) c[4] : (int) c[5]);
        if (yv == Double.NEGATIVE_INFINITY) return;
        int dist = al < c[4] ? (int) c[4] - al : al - (int) c[5];
        double own = m.surface(i), ye = dist <= 1 ? yv : own > yv ? Math.min(own, yv + 0.5 * (dist - 1)) : Math.max(own, yv - 0.5 * (dist - 1));
        int t = (int) Math.floor(ye);
        if (wy != StreamsReflowing.NONE) {
            if (wy >= t) return;
            set(x, t, z, Palette.bridgeWood(streamPair(m, i), false).planks());
            clear(x, t + 1, t + 3, z);
            return;
        }
        int g = groundAt(x, z, t);
        BlockState natural = get(x, g, z);
        if (g > t) clear(x, t + 1, g + 2, z);
        else fillBelow(x, z, g, t);
        if (!PaintRules.solid(get(x, t - 1, z))) set(x, t - 1, z, Blocks.DIRT.defaultBlockState());
        Palette.Surface lp = pal(x, t, z);
        set(x, t, z, Palette.surface(lp, 1f, hash(x, z), natural, g == t));
        clear(x, t + 1, t + 3, z);
        if (ye - t >= 0.5) set(x, t + 1, z, lp.step().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    }

    /** A stream deck's surface at s: its own, raised to the lower of the highest planned heights before and after s. */
    private static double deckLevel(EdgePlan p, float[] c, float s, double own) {
        int n = p.length();
        float a = Float.isInfinite(c[0]) ? s - 8 : c[0], b = Float.isInfinite(c[1]) ? s + 8 : c[1];
        double pre = own, suf = own;
        for (int k = Math.max(0, (int) Math.ceil(a)); k <= Math.min(n - 1, (int) Math.floor(b)); k++) {
            if (k <= s) pre = Math.max(pre, p.y[k]);
            if (k >= s) suf = Math.max(suf, p.y[k]);
        }
        return Math.max(own, Math.min(pre, suf));
    }

    /** The highest planned surface of a squared crossing's columns (deck and rails) at one place along its axis. */
    private double squareRow(ChunkMask m, float[] c, int al) {
        double yv = Double.NEGATIVE_INFINITY;
        if (al < c[4] || al > c[5]) return yv;
        for (int o = -(int) c[7] - 1; o <= (int) c[7] + 1; o++) {
            int rx = c[3] == 0 ? al : (int) c[6] + o, rz = c[3] == 0 ? (int) c[6] + o : al;
            if (!inChunk(rx, rz)) continue;
            int j = ChunkMask.idx(rx & 15, rz & 15);
            if (m.in[j] != Float.NEGATIVE_INFINITY) yv = Math.max(yv, m.surface(j));
        }
        return yv;
    }

    /** Under a stream bridge's deck (and stringer): a ford row's bed one lower, dry rows filled, air pockets over dry ground filled. */
    private int underDeck(int x, int z, int top, int ground, int wy, boolean deck) {
        if (wy == top && PaintRules.solid(get(x, top - 1, z)) && get(x, top - 1, z).getFluidState().isEmpty()
            && (PaintRules.solid(get(x, top - 2, z)) || !get(x, top - 2, z).getFluidState().isEmpty())) {
            // a ford row inside a crossing: the bed goes one down under the deck so the stream runs on under the planks
            // (laid on the ford's water, the deck dammed it)
            set(x, top - 1, z, Blocks.WATER.defaultBlockState());
            wy = top - 1;
        }
        if (!deck) return wy;
        if (wy == StreamsReflowing.NONE || wy >= top) fillBelow(x, z, ground, top);
        else {
            // over the stream: the water stays; air under the deck with no water below it is filled down to the bed
            int low = top - 1;
            while (low > wy && get(x, low, z).isAir()) low--;
            if (low == wy && !get(x, wy, z).getFluidState().isEmpty()) low = top;
            for (int y = top - 1; y > low; y--) set(x, y, z, Blocks.DIRT.defaultBlockState());
        }
        if (ground > top + 2) clear(x, top + 5, ground + 2, z);
        return wy;
    }

    /** The stream crossing this column's row lies in, or null. */
    private float[] crossing(ChunkMask m, int i) {
        if (crossings.isEmpty() || m.plan[i] == null || m.kind[i] != EdgePlan.GROUND) return null;
        java.util.List<float[]> l = crossings.get(m.plan[i]);
        if (l == null) return null;
        for (float[] c : l) if (m.s[i] >= c[0] && m.s[i] <= c[1]) return c;
        return null;
    }

    /** Post row ids that survive a float (exact below 2^24). */
    private static final int NO_BENT = -9_000_001, START_BENT = -9_000_002, END_BENT = -9_000_003;

    /**
     * The post row a stream bridge column stands in (NO_BENT if none) and its offset from the nearest post row, for
     * the knee braces: {id, offset}. End posts (START/END) stand on a crossing's known ends; between them the posts
     * stand every TIMBER_BAY samples counted from the plan's start, never within 3.5 blocks of an end, so chunks that
     * share a crossing's rows (a road along a chunk border has one rail in each) agree whatever ends each knows (evenly
     * spaced posts between local ends left the two rails' posts out of step). w: one block's width in samples on this
     * row, per: samples per block.
     */
    private static float[] streamBent(float s, float[] c, float w, float per) {
        if (Math.abs(s - c[0] - 0.5f) < w / 2) return new float[]{START_BENT, s - c[0] - 0.5f};
        if (Math.abs(c[1] - s - 0.5f) < w / 2) return new float[]{END_BENT, s - c[1] + 0.5f};
        int jb = Math.round(s / TIMBER_BAY);
        float at = jb * TIMBER_BAY, off = s - at;
        if (at - c[0] < STREAM_END_GAP * per || c[1] - at < STREAM_END_GAP * per) return new float[]{NO_BENT, Float.MAX_VALUE};
        return new float[]{Math.abs(off) < w / 2 ? jb : NO_BENT, off};
    }

    /**
     * The post row (as streamBent) whose place lies in (ps, s]: ps is the previous column of the same rail line. Every
     * post row then lands on exactly one column of each rail, also where a diagonal rail's stair steps skip past it (the
     * block-width test left one rail of a pair without its post: a lone lantern post, critic 2026-11-18).
     */
    private static int railBent(float ps, float s, float[] c, float per) {
        if (c[0] > Float.NEGATIVE_INFINITY && c[0] + 0.5f > ps && c[0] + 0.5f <= s) return START_BENT;
        if (c[1] < Float.POSITIVE_INFINITY && c[1] - 0.5f > ps && c[1] - 0.5f <= s) return END_BENT;
        for (int jb = (int) Math.floor(ps / TIMBER_BAY) + 1; jb * TIMBER_BAY <= s; jb++) {
            float at = jb * TIMBER_BAY;
            if (at - c[0] < STREAM_END_GAP * per || c[1] - at < STREAM_END_GAP * per) continue;
            return jb;
        }
        return NO_BENT;
    }

    /**
     * One column of a stream crossing: road columns are the deck (open water kept under it, air pockets over dry ground
     * filled, dry rows on solid fill), the rail line half a block out is the stringer with its fence, posts and lanterns,
     * exactly as on the road's own timber bridges.
     */
    private void streamBridge(int x, int z, int top, boolean half, float d, ChunkMask m, int i, int ground, float[] c, int wy) {
        // a deck never dips: where the road's planned height sags across the crossing the deck holds the height of the
        // higher ground either side (a middle span a block lower read as a broken bridge, critic 2026-11-18)
        double own = m.surface(i), yd = deckLevel(m.plan[i], c, m.s[i], own);
        if (yd > own) {
            int nt = (int) Math.floor(yd);
            if (nt != top) ground = groundAt(x, z, nt);
            top = nt;
            half = yd - nt >= 0.5;
            if (wy != StreamsReflowing.NONE && wy > top) return;
        }
        // the whole 4-connected rail band, also its columns just inside the road edge: on a diagonal those fill the
        // line's stair steps (with only d >= 0 the rail had diagonal gaps, and a post row falling in one left its rail
        // without a post)
        float sl = m.slant[i] / 2;
        boolean rail = d - 0.5f >= -sl && d - 0.5f < sl, deck = !rail;
        int pair = streamPair(m, i);
        Palette.BridgeWood main = Palette.bridgeWood(pair, false), trim = Palette.bridgeWood(pair, true);
        Direction along = Direction.getApproximateNearest(m.tx[i], 0, m.tz[i]);
        Direction out = Direction.getApproximateNearest(m.ox[i], 0, m.oz[i]);
        if (out.getAxis() == along.getAxis() || out.getAxis() == Direction.Axis.Y) out = along.getClockWise();
        float s = m.s[i], w = m.ds[i] * Math.max(Math.abs(m.tx[i]), Math.abs(m.tz[i]));
        float per = Math.max(0.5f, m.ds[i]);
        float[] b = streamBent(s, c, w, per);
        int id = (int) b[0];
        if (rail) {
            float pu = prevU(d, m, i);
            if (!Float.isNaN(pu)) id = railBent(s + (pu - m.runU[i]), s, c, per);
        }
        boolean bent = id != NO_BENT, end = id == START_BENT || id == END_BENT;
        if (rail && bent && end && m.joint[i]) bent = false;
        if (io.github.profetgit.countryroads.gen.GenContext.debug(x, z))
            io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE sbridge " + x + "," + z + " s=" + s + " d=" + d + " rail=" + rail + " id=" + id
                + " pu=" + (rail ? prevU(d, m, i) : Float.NaN) + " runU=" + m.runU[i] + " c=" + c[0] + ".." + c[1] + " f=" + (int) c[2] + " slant=" + m.slant[i]);
        underDeck(x, z, top, ground, wy, deck);
        float off = b[1];
        boolean brace = !bent && Math.abs(off) < 1.5f * w && Math.abs(off) >= w / 2;
        boolean nearEnd = s - c[0] < 1.5f || c[1] - s < 1.5f;
        // a deck running on to one of the road's planned bridges has no end post there (the bridge's own end posts stand
        // there) and no post within STREAM_QUIET blocks of that bridge; told from the plan, so every chunk of the deck agrees
        if (bent && (((int) c[2] & 1) != 0 && id == START_BENT || ((int) c[2] & 2) != 0 && id == END_BENT
            || nearBridgeEnd(m.plan[i], s, STREAM_QUIET * per))) bent = false;
        // lanterns on every other post row, never on one closer than STREAM_LAMP_GAP to a lit end post or a planned
        // bridge's (two lit pairs a few blocks apart read as lanterns side by side, critic 2026-11-18)
        boolean lamp = end ? (c[1] - c[0]) / per >= 6 || (id == START_BENT) == (m.side[i] > 0)
            : id != NO_BENT && Math.floorMod(id, 2) == 0 && s - c[0] >= STREAM_LAMP_GAP * per && c[1] - s >= STREAM_LAMP_GAP * per
                && !nearBridgeEnd(m.plan[i], s, STREAM_LAMP_GAP * per);
        timberColumn(x, z, top, half, rail, deck, false, true, main, trim, along, out, top - 2, ground, bent, nearEnd, brace, off > 0, lamp, true);
    }

    /** Blocks from one of the road's planned bridges within which a stream deck running on to it has no post of its own. */
    static final float STREAM_QUIET = 10f;

    /** Samples from a stream crossing's end posts within which no post row stands (4 blocks between them at least). */
    static final float STREAM_END_GAP = 4.5f;

    /** Samples from a lit end post (or a planned bridge) within which a post row carries no lantern. */
    static final float STREAM_LAMP_GAP = 7f;

    /** One of the plan's planned bridge samples within reach of s, with only ground between. */
    private static boolean nearBridgeEnd(EdgePlan p, float s, float reach) {
        int n = p.length();
        for (int dir = -1; dir <= 1; dir += 2)
            for (int k = 0; k <= (int) Math.ceil(reach); k++) {
                int q = dir > 0 ? (int) Math.ceil(s) + k : (int) Math.floor(s) - k;
                if (q < 0 || q >= n || Math.abs(q - s) > reach) break;
                if (p.kind[q] == EdgePlan.BRIDGE) return true;
                if (p.kind[q] != EdgePlan.GROUND) break;
            }
        return false;
    }

    /**
     * How far a column lies outside a stream deck's edge (negative on the deck): the deck has the width of the road's own
     * timber bridges (Planner.BRIDGE_HW, 3 columns on a column-centred line), not the wider ground road's, so its rails
     * run on in line with a planned bridge it meets and every stream bridge looks like the planned ones.
     */
    private static float deckD(ChunkMask m, int i) {
        return -m.in[i] + Math.max(0f, (float) at(m.plan[i], m.s[i])[3] - DECK_HW);
    }

    /** Half width of a stream deck: Planner.BRIDGE_HW. */
    static final float DECK_HW = 1.75f;

    /**
     * Where a player stands on a walkway crossing's dry row: on the walkway (lv + 0.5) next to its water, climbing half a
     * block per row (in blocks from the nearest wet row) toward the road's own height, never over it.
     */
    private static double walkFeet(float[] c, int lv, float s, float per, double own) {
        if (c.length < 12) return lv + 0.5;
        float rows = Math.max(0f, Math.max(c[10] - s, s - c[11])) / per;
        return Math.max(lv + 0.5, Math.min(own, lv + 0.5 + 0.5 * Math.ceil(rows)));
    }

    /** The height of a walkway crossing's pieces: a block over its highest water (else over this column's). */
    private static int walkLevel(float[] c, int top, int wy) {
        if (c.length > 9 && c[9] > Integer.MIN_VALUE / 2f) return (int) c[9];
        return wy != StreamsReflowing.NONE && wy >= top ? top + 1 : top;
    }

    /**
     * Beside a stream walkway: a block standing at the walkway's layer next to a piece, over the stream's water, goes
     * (a lone dirt-path block at a walkway's end, outside the crossing's rows, read as a pillar; critic 2026-11-18).
     */
    private void walkLumps(ChunkMask m) {
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int i = ChunkMask.idx(lx, lz);
                if (m.in[i] == Float.NEGATIVE_INFINITY || m.in[i] >= 0) continue;
                int x = (m.cx << 4) + lx, z = (m.cz << 4) + lz, top = (int) Math.floor(m.surface(i));
                for (int y = top - 1; y <= top + 1; y++) {
                    BlockState here = get(x, y, z);
                    if (!PaintRules.terrain(here) && !here.is(Blocks.DIRT_PATH) || !get(x, y + 1, z).isAir() && !get(x, y + 1, z).canBeReplaced()) continue;
                    boolean piece = false;
                    for (int dx = -1; dx <= 1 && !piece; dx++)
                        for (int dz = -1; dz <= 1 && !piece; dz++)
                            if ((dx != 0 || dz != 0) && inChunk(x + dx, z + dz) && walkwayPiece(get(x + dx, y, z + dz))) piece = true;
                    if (!piece || wetAround(x, z, y - 1) < 1) continue;
                    clear(x, y, y + 2, z);
                    BlockState below = get(x, y - 1, z);
                    if (PaintRules.solid(below) && below.getFluidState().isEmpty()) set(x, y - 1, z, PaintRules.surfaceOf(below));
                    break;
                }
            }
    }

    /** In-chunk side neighbours with water at y (a lump standing in a stream has two or more). */
    private int wetAround(int x, int z, int y) {
        int n = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dd : dirs)
            if (inChunk(x + dd[0], z + dd[1]) && !get(x + dd[0], y, z + dd[1]).getFluidState().isEmpty()) n++;
        return n;
    }

    /** A beside column a signpost or lamp must not stand on: stream water, or the rail line of a stream bridge. */
    private boolean streamBlocked(ChunkMask m, int x, int z, int top) {
        if (streamWater(x, z, top) != StreamsReflowing.NONE) return true;
        if (!inChunk(x, z)) return false;
        if (!crossings.isEmpty() && squareAt(x, z) != null) return true;
        int i = ChunkMask.idx(x & 15, z & 15);
        float[] c = crossing(m, i);
        if (c == null || c[3] >= 0) return false;
        float d = deckD(m, i);
        return d - 0.5f >= -m.slant[i] / 2 && d - 0.5f < m.slant[i] / 2;
    }

    /** Blocks of deck past the last wet row, each side; dry stretches up to STREAM_GAP between wet rows are decked. */
    static final float STREAM_LANDING = 1.5f, STREAM_GAP = 6f;

    /** Blocks of road under a stream's water (ford rows only) up to which it stays a ford, the water on the road. */
    static final float STREAM_FORD_MAX = 2f;

    /** Crossings closer than this (blocks between their ends) are one bridge. */
    static final float STREAM_JOIN = 4f;

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
                along, out, level, ground, h, rail ? prevU(d, m, i) : Float.NaN, m.joint[i]);
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
        return lantern(x, y, z, Blocks.LANTERN.defaultBlockState(), false);
    }

    /**
     * A lantern unless another stands in its 26 neighbours (same chunk). Every lantern goes through here: tunnel rows
     * (one per column across the bore) and lamp posts beside a bridge once put two side by side.
     */
    private boolean lantern(int x, int y, int z, BlockState state, boolean exactly) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    if ((dx | dy | dz) == 0 || (x + dx) >> 4 != x >> 4 || (z + dz) >> 4 != z >> 4) continue;
                    if (get(x + dx, y + dy, z + dz).is(Blocks.LANTERN)) return false;
                }
        if (exactly) exact(x, y, z, state);
        else set(x, y, z, state);
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
                              Palette.BridgeWood main, Palette.BridgeWood trim, Direction along, Direction out, int level, int ground, long h,
                              float uPrev, boolean joint) {
        int bays = Math.max(1, Math.round((L - 1) / TIMBER_BAY));
        float step = (L - 1) / bays;
        int j = Math.clamp(Math.round((u - 0.5f) / step), 0, bays);
        float off = u - 0.5f - j * step;
        boolean bent = planned ? Math.abs(off) < ds / 2 : Math.abs(u - 5 * Math.round(u / 5)) < ds / 2;
        boolean end = planned && (u < ds || u > L - ds);
        // as on stone bridges: where the road bends at a bridge end the outer rail columns all sit at the joint's u, and
        // on a stair-stepped rail both blocks of a step fall in one bent's band; only the first takes the post (three
        // posts and two lanterns stood clustered at one end of a timber bridge, none at the other rail's end)
        if (rail && planned && bent) {
            if (end && joint) bent = false;
            else if (!Float.isNaN(uPrev) && uPrev >= 0) {
                int jp = Math.clamp(Math.round((uPrev - 0.5f) / step), 0, bays);
                if (jp == j && Math.abs(uPrev - 0.5f - jp * step) < ds / 2) bent = false;
            }
        }
        timberColumn(x, z, top, half, rail, deck, outer, planned, main, trim, along, out, level, ground, bent,
            planned && (u < 1.5f || u > L - 1.5f), planned && !bent && Math.abs(off) < 1.5f * ds, off > 0, planned && (end || j % 2 == 0), false);
    }

    /**
     * One column of a timber bridge once its place along the crossing is known: bent (a post row), nearEnd (earth under
     * the deck), brace (a knee brace under the stringer, braceBack: the bent lies behind it), lampWanted (a lantern on
     * this post). stream: the crossing is over another mod's stream at the road's own height, so a cross beam or end fill
     * never goes into its water.
     */
    private void timberColumn(int x, int z, int top, boolean half, boolean rail, boolean deck, boolean outer, boolean planned,
                              Palette.BridgeWood main, Palette.BridgeWood trim, Direction along, Direction out, int level, int ground,
                              boolean bent, boolean nearEnd, boolean brace, boolean braceBack, boolean lampWanted, boolean stream) {
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
        if (nearEnd && top - 1 - ground <= 3 && (!stream || dryBelow(x, z, ground, top))) {
            // the bank runs on under the bridge's ends
            for (int y = top - 1; y > ground; y--) set(x, y, z, Blocks.DIRT.defaultBlockState());
        } else if (rail) {
            // knee braces either side of each bent, under the stringer
            if (brace && get(x, top - 1, z).isAir())
                set(x, top - 1, z, stair(trim.stairs(), braceBack ? along.getOpposite() : along, true));
        } else if (bent && (!stream || get(x, top - 1, z).getFluidState().isEmpty())) {
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
            boolean lamp = lampWanted && lantern(x, top + 3, z);
            clear(x, top + (lamp ? 4 : 3), top + 4, z);
        } else {
            set(x, top + 1, z, main.fence());
            clear(x, top + 2, top + 4, z);
        }
    }

    /** No water between the ground and the deck. */
    private boolean dryBelow(int x, int z, int ground, int top) {
        for (int y = ground + 1; y < top; y++) if (!get(x, y, z).getFluidState().isEmpty()) return false;
        return true;
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

        if (kind == EdgePlan.GROUND && StreamsReflowing.ON) {
            // a stream another mod carved beside a ground road keeps its water: no bank, fence or verge over it; on a
            // stream crossing's rows the rail line is the bridge's, and no fence runs beside it
            int wy = streamWater(x, z, top);
            float[] cr = crossing(m, i);
            float[] sq = crossings.isEmpty() ? null : squareAt(x, z);
            if (sq != null) {
                if (wy == StreamsReflowing.NONE || wy <= top) squareBridge(x, z, top, m, i, sq, wy);
                return;
            }
            float[] se = crossings.isEmpty() || wy != StreamsReflowing.NONE ? null : nearSquareEnd(x, z);
            if (se != null) {
                // natural shore standing at or over the deck beside a squared crossing's end posts goes down a block
                // under the deck (a bare stone block against the end post read as debris, critic 2026-11-18)
                double lv = Double.NEGATIVE_INFINITY;
                for (int a = (int) se[4]; a <= (int) se[5]; a++) lv = Math.max(lv, squareRow(m, se, a));
                int t = (int) Math.floor(lv);
                if (lv > Double.NEGATIVE_INFINITY && ground >= t) {
                    BlockState natural = PaintRules.surfaceOf(get(x, ground, z));
                    clear(x, t - 1, ground + 2, z);
                    set(x, t - 1, z, natural);
                    return;
                }
            }
            if (cr != null && cr[3] >= 0) {
                if (wy != StreamsReflowing.NONE) return;
                cr = null;
            }
            if (cr != null && ((int) cr[2] & 4) != 0) {
                int lv = walkLevel(cr, top, wy);
                if (wy != StreamsReflowing.NONE && wy < lv) {
                    if (d < 4.5f) walkway(x, z, lv, d, m, i);
                    return;
                }
                if (wy == StreamsReflowing.NONE && d < 2f && ground >= lv) {
                    // natural ground beside the walkway standing at or over its layer goes down to the water (a grass
                    // block a block over the walkway read as a lump, critic 2026-11-18)
                    BlockState natural = PaintRules.surfaceOf(get(x, ground, z));
                    clear(x, lv - 1, ground + 2, z);
                    set(x, lv - 1, z, natural);
                    return;
                }
                cr = null;
            }
            if (cr != null) {
                float sl = m.slant[i] / 2, dd = deckD(m, i);
                if (dd - 0.5f >= -sl && dd - 0.5f < sl) {
                    if (wy == StreamsReflowing.NONE || wy <= top) streamBridge(x, z, top, false, dd, m, i, ground, cr, wy);
                    return;
                }
                if (wy != StreamsReflowing.NONE) return;
                if (ground > top && ground <= top + 2 && (dd < 2f || dd < 3f && wetAround(x, z, top) >= 2)) {
                    // a lump of the old ground standing in the stream beside the deck goes down to the deck's level (a
                    // lone dark block beside the rail read as debris)
                    BlockState natural = PaintRules.surfaceOf(get(x, ground, z));
                    clear(x, top + 1, ground + 2, z);
                    set(x, top, z, natural);
                    return;
                }
                shoulder(x, z, top, d, ground, PaintRules.surfaceOf(get(x, ground, z)), h, m.y[i]);
                verge(x, z, top, d);
                return;
            }
            if (wy != StreamsReflowing.NONE) return;
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
        if (propCols != null && propCols[ChunkMask.idx(x & 15, z & 15)] || fieldCol(x, z)) return;
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
        // flowers and bushes 1 in 4 (user 2026-10-16), grass as before
        if (!plant.is(Blocks.SHORT_GRASS) && !plant.is(Blocks.TALL_GRASS) && !plant.is(Blocks.SHORT_DRY_GRASS) && ((h >>> 44) & 3) != 0) return;
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
        if ((int) (h & 1023) >= (int) (1024 * WILD_SHARE * wild.density() * Palette.wildRow(d))) return;
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
