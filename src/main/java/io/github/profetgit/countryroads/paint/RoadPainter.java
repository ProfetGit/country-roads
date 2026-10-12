package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.gen.GenContext;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
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
    static final float BANK_REACH = 5f;

    private final Canvas chunk;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private RoadPainter(Canvas chunk) {
        this.chunk = chunk;
    }

    /** Worldgen: paints a generating chunk and records its road columns (empty too: "this chunk was handled"). */
    public static int paint(ChunkAccess chunk, ChunkMask m) {
        RoadColumns cols = RoadColumns.empty();
        int painted = m.any() ? paint(Canvas.proto(chunk), m, cols) : 0;
        RoadColumns.store(chunk, cols);
        return painted;
    }

    public static int paint(Canvas canvas, ChunkMask m, RoadColumns cols) {
        if (!m.any()) return 0;
        RoadPainter p = new RoadPainter(canvas);
        int painted = 0;
        GenContext.bypass(true);
        try {
            // the road first, then banks and walls beside it (they read the finished road columns)
            for (int pass = 0; pass < 2; pass++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int i = ChunkMask.idx(lx, lz);
                        float in = m.in[i];
                        if (in == Float.NEGATIVE_INFINITY) continue;
                        double q = m.surface(i);
                        int top = (int) Math.floor(q);
                        if (top <= canvas.minY() + 4 || top >= canvas.maxY() - 8) continue;
                        int x = (m.cx << 4) + lx, z = (m.cz << 4) + lz;
                        if (pass == 0 && in >= 0) {
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
            for (EdgePlan.Decor d : m.decor) p.decor(d, m);
        } finally {
            GenContext.bypass(false);
        }
        return painted;
    }

    private void decor(EdgePlan.Decor d, ChunkMask m) {
        int i = ChunkMask.idx(d.x() & 15, d.z() & 15);
        if (m.in[i] >= 0) return;
        int top = (int) Math.floor(Math.round(d.y() * 2) / 2.0);
        int x = d.x(), z = d.z();
        int g = groundAt(x, z, top);
        if (Math.abs(g - top) > 4 || !PaintRules.terrain(get(x, g, z))) return;
        Palette.Wood wood = Palette.wood(m.in[i] == Float.NEGATIVE_INFINITY ? 0 : m.wood[i]);
        long h = hash(x, z);
        int ax = x + d.toRoad().getStepX(), az = z + d.toRoad().getStepZ();
        clear(x, g + 1, g + 5, z);
        // a gallows post: stone foot, three fences, an arm reaching over the road edge; a hanging sign or a lantern
        // hangs from the arm. States are set exactly so the post never latches onto banks or grass beside it.
        net.minecraft.core.Direction toRoad = d.toRoad();
        BlockState foot = (((h >>> 5) & 1) == 0 ? Blocks.MOSSY_COBBLESTONE_WALL : Blocks.COBBLESTONE_WALL).defaultBlockState();
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

    /** Dry-stone wall or split-rail fence on the shoulder, where a run says so and the shoulder is level with the road. */
    private void wallOrFence(int x, int z, int top, float d, ChunkMask m, int i) {
        if (m.run[i] < 0 || m.kind[i] != EdgePlan.GROUND) return;
        int g = groundAt(x, z, top);
        if (!PaintRules.terrain(get(x, g, z))) return;
        int s = (int) Math.floor(m.s[i]);
        long h = hash(x, z);
        if (m.run[i] == EdgePlan.STONE_WALL && g == top && (d >= 2.1f && d < 3.1f && ((h >>> 50) & 7) < 3 || d >= 0.6f && d < 1.0f && ((h >>> 50) & 15) < 2)) {
            // the wall is not a single line: loose stones and a few low courses either side of it
            clear(x, g + 1, g + 2, z);
            set(x, g + 1, z, ((h >>> 54) & 3) == 0 ? Palette.wallBody(h) : Palette.wallCap(h >>> 3) == null ? Blocks.COBBLESTONE_SLAB.defaultBlockState()
                : Palette.wallCap(h >>> 3).hasProperty(net.minecraft.world.level.block.StairBlock.FACING) ? Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState() : Palette.wallCap(h >>> 3));
            return;
        }
        if (d < 1.0f || d >= 2.1f) return;
        if (m.run[i] == EdgePlan.FENCE ? g != top : Math.abs(g - top) > 1) return;
        if (m.run[i] == EdgePlan.STONE_WALL) {
            if (Math.floorMod(s, 17) == 0) return;
            clear(x, g + 1, g + 3, z);
            boolean low = ((h >>> 3) & 7) == 0;
            if (!low) set(x, g + 1, z, Palette.wallBody(h));
            BlockState cap = Palette.wallCap(h >>> 7);
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
            if (!get(x, y, z).isAir()) set(x, y, z, AIR);
        }
    }

    static final String DEBUG = System.getProperty("countryroads.debugColumn", "");

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
        if (kind == EdgePlan.GROUND && top - ground - 1 > MAX_FILL) kind = EdgePlan.BRIDGE;
        if (kind == EdgePlan.TUNNEL && ground <= top + 5) kind = EdgePlan.GROUND;

        switch (kind) {
            case EdgePlan.BOARDWALK -> {
                set(x, top, z, wood.planks().defaultBlockState());
                if (half) set(x, top + 1, z, wood.slab().defaultBlockState());
                if (in < 0.9f && Math.floorMod(s, 4) == 0) post(x, z, top - 1, wood.log().defaultBlockState());
                clear(x, top + (half ? 2 : 1), top + 4, z);
            }
            case EdgePlan.BRIDGE -> {
                boolean stone = m.runLen[i] > 20 && top - ground > 2;
                set(x, top, z, stone ? Palette.masonry(h) : wood.planks().defaultBlockState());
                if (half) set(x, top + 1, z, (stone ? Blocks.STONE_BRICK_SLAB : wood.slab()).defaultBlockState());
                if (stone) {
                    int u = Math.floorMod(s, 10);
                    if (u == 0) post(x, z, top - 1, Palette.masonry(h));
                    else {
                        int arch = (int) Math.round(2.2 * (1 - Math.sin(Math.PI * u / 10.0)));
                        for (int y = top - 1; y >= top - 1 - arch && y > ground; y--) set(x, y, z, Palette.masonry(h + y));
                    }
                } else if (in < 0.9f && Math.floorMod(s, 5) == 0) {
                    post(x, z, top - 1, wood.log().defaultBlockState());
                }
                clear(x, top + (half ? 2 : 1), top + 4, z);
            }
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
                clear(x, from, Math.max(top + 4, ground + 2), z);
                if (ground > top + 1) clearLeaves(x, ground + 1, ground + 10, z);
            }
        }
    }

    /** Columns beside the road: rails, tunnel walls, banks, embankments, retaining walls. */
    private void beside(int x, int z, int top, float d, ChunkMask m, int i) {
        if (d >= BANK_REACH) return;
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
        if (kind == EdgePlan.GROUND && top - ground - 1 > MAX_FILL && d < 1) kind = EdgePlan.BRIDGE;
        if (kind == EdgePlan.TUNNEL && ground <= top + 5) kind = EdgePlan.GROUND;

        if ((kind == EdgePlan.BRIDGE) && d < 1) {
            boolean stone = m.runLen[i] > 20 && top - ground > 2;
            set(x, top, z, stone ? Palette.masonry(h) : wood.planks().defaultBlockState());
            set(x, top + 1, z, stone ? Blocks.STONE_BRICK_WALL.defaultBlockState() : wood.fence().defaultBlockState());
            if (Math.floorMod(s, 10) == 0) set(x, top + 2, z, Blocks.LANTERN.defaultBlockState());
            if (stone && Math.floorMod(s, 10) == 0) post(x, z, top - 1, Palette.masonry(h));
            else if (!stone && Math.floorMod(s, 5) == 0) post(x, z, top - 1, wood.log().defaultBlockState());
            clear(x, top + 2 + (Math.floorMod(s, 10) == 0 ? 1 : 0), top + 4, z);
            return;
        }
        if (kind == EdgePlan.BOARDWALK || kind == EdgePlan.BRIDGE) return;
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
    }

    private void shoulder(int x, int z, int top, float d, int ground, BlockState natural, long h, float roadY) {
        if (ground == top + 1 && d < 1.5f) {
            // flush shoulder: no one-block lip along the road
            clear(x, top + 1, top + 3, z);
            set(x, top, z, natural);
        } else if (ground > top + 1) {
            // cut: a 1:1 bank up from the road edge (beyond the reach the hillside keeps its own face); measured from the
            // road's smooth height, so the bank's steps follow the road instead of each column's rounding
            int bank = Math.max(top, Math.round(roadY + Math.max(0f, d - 0.5f)));
            if (ground > bank) {
                clear(x, bank + 1, ground + 2, z);
                clearLeaves(x, ground + 3, ground + 10, z);
                set(x, bank, z, natural);
            }
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
        }
        feather(x, z, top, d, h);
    }

    /** The road frays into the verge: flush columns just outside the edge sometimes take an edge block. */
    private void feather(int x, int z, int top, float d, long h) {
        if (d >= 1.6f) return;
        BlockState here = get(x, top, z);
        if (!PaintRules.terrain(here) || PaintRules.solid(get(x, top + 1, z))) return;
        int r = (int) ((h >>> 33) & 1023);
        if (r >= (int) (400 * (1 - d / 1.6f))) return;
        Palette.Surface pal = pal(x, top, z);
        BlockState edge = pal.pick(pal.edge(), pal.edgeW(), (int) ((h >>> 43) & 1023));
        clear(x, top + 1, top + 2, z);
        set(x, top, z, edge);
    }

    private void clearLeaves(int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) if (get(x, y, z).is(BlockTags.LEAVES)) set(x, y, z, AIR);
    }

    private Palette.Surface pal(int x, int y, int z) {
        return Palette.surface(chunk.biome(x, y, z));
    }

    private void fillBelow(int x, int z, int ground, int top) {
        for (int y = ground + 1; y < top; y++) set(x, y, z, top - y <= 3 ? Blocks.DIRT.defaultBlockState() : Palette.rubble(hash(x, z) + y));
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
