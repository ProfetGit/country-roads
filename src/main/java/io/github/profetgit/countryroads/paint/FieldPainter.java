package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lays the wheat fields of one chunk (ChunkMask.fields) after the road and its verge are shaped: farmland with wheat
 * in soft ripeness patches on the column's own ground, water pockets on a world grid, a ragged headland, fence and
 * hedge fragments on the outer rim and a scarecrow. Every decision is a function of the field and the column's own
 * blocks, so neighbouring chunks agree; pockets need all four neighbours inside this chunk.
 */
public final class FieldPainter {
    private FieldPainter() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int NONE = Integer.MIN_VALUE;

    /** Which field owns each column (null: none) as a field column, and as a headland column. */
    public record Cols(Field[] field, Field[] head) {}

    /** Geometric field and headland columns: inside a blob, off the road and everything a field may not take. */
    public static Cols columns(ChunkMask m, boolean[] propCols) {
        if (m.fields.isEmpty()) return null;
        Field[] own = new Field[256], head = new Field[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int i = ChunkMask.idx(lx, lz), x = (m.cx << 4) + lx, z = (m.cz << 4) + lz;
                if (m.nofield[i] || propCols != null && propCols[i]) continue;
                float in = m.in[i];
                boolean data = in != Float.NEGATIVE_INFINITY;
                if (data && (in >= -0.6f || m.kind[i] != EdgePlan.GROUND || m.conn[i] || m.run[i] >= 0 && in >= -3.5f)) continue;
                for (Field f : m.fields) {
                    int st = f.state(x, z);
                    if (st == 0) continue;
                    if (st == 1) {
                        if (head[i] == null && own[i] == null) head[i] = f;
                        continue;
                    }
                    if (data && in >= -1.6f && f.pushedBack(x, z, m.side[i], m.tx[i], m.tz[i])) continue;
                    own[i] = f;
                    head[i] = null;
                    break;
                }
            }
        return new Cols(own, head);
    }

    private static BlockState get(Canvas cv, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        return cv.get(pos.set(x, y, z));
    }

    private static void set(Canvas cv, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
        if (y <= cv.minY() || y >= cv.maxY()) return;
        cv.set(pos.set(x, y, z), s);
    }

    /** Sets a block where a plant may have stood: both halves of a tall plant go. */
    private static void put(Canvas cv, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
        BlockState here = get(cv, pos, x, y, z);
        if (!here.isAir() && Palette.upperHalf(here) != null && get(cv, pos, x, y + 1, z).is(here.getBlock())) set(cv, pos, x, y + 1, z, AIR);
        set(cv, pos, x, y, z, s);
    }

    private static boolean clearable(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return false;
        return s.canBeReplaced() || s.getBlock() instanceof VegetationBlock;
    }

    private static boolean woodyOrLeaves(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static int groundTop(Canvas cv, BlockPos.MutableBlockPos pos, int x, int z, int near) {
        int hi = Math.min(cv.maxY() - 8, near + 14), lo = Math.max(cv.minY() + 2, near - 20);
        for (int y = hi; y >= lo; y--) if (PaintRules.terrain(get(cv, pos, x, y, z))) return y;
        return NONE;
    }

    /** Natural grass or dirt with a plant, snow or nothing above, no water at or above, no tree in the next 4 blocks. */
    private static int fieldGround(Canvas cv, BlockPos.MutableBlockPos pos, int x, int z, int near) {
        int g = groundTop(cv, pos, x, z, near);
        if (g == NONE) return NONE;
        BlockState s = get(cv, pos, x, g, z);
        if (!s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.DIRT) && !s.is(Blocks.COARSE_DIRT)) return NONE;
        if (!clearable(get(cv, pos, x, g + 1, z))) return NONE;
        for (int y = g + 1; y <= g + 4; y++) {
            BlockState a = get(cv, pos, x, y, z);
            if (woodyOrLeaves(a) || !a.getFluidState().isEmpty()) return NONE;
        }
        return g;
    }

    private static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL);
    }

    private static BlockState leaves(int wood) {
        return (switch (wood) {
            case 0 -> Blocks.SPRUCE_LEAVES;
            case 2 -> Blocks.MANGROVE_LEAVES;
            case 3 -> Blocks.DARK_OAK_LEAVES;
            case 4 -> Blocks.JUNGLE_LEAVES;
            default -> Blocks.OAK_LEAVES;
        }).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private static Direction dir(int dx, int dz) {
        return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static void paint(Canvas cv, ChunkMask m, Cols cols, boolean[] propCols) {
        if (cols == null) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = m.cx << 4, bz = m.cz << 4;
        int[] g = new int[256];
        boolean[] ok = new boolean[256];
        for (int i = 0; i < 256; i++) {
            Field f = cols.field[i];
            if (f == null) continue;
            g[i] = fieldGround(cv, pos, bx + (i & 15), bz + (i >> 4), f.y);
            ok[i] = g[i] != NONE;
        }
        // scarecrow: pole and both arm columns must be field columns on level ground
        boolean[] reserved = new boolean[256];
        java.util.List<Field> scarecrows = new java.util.ArrayList<>();
        for (Field f : m.fields) {
            if (!f.hasScarecrow || f.sx >> 4 != m.cx || f.sz >> 4 != m.cz) continue;
            int p = ChunkMask.idx(f.sx & 15, f.sz & 15), a = ChunkMask.idx((f.sx - f.armX) & 15, (f.sz - f.armZ) & 15), b = ChunkMask.idx((f.sx + f.armX) & 15, (f.sz + f.armZ) & 15);
            if (cols.field[p] != f || cols.field[a] != f || cols.field[b] != f || !ok[p] || !ok[a] || !ok[b] || g[a] != g[p] || g[b] != g[p]) continue;
            reserved[p] = reserved[a] = reserved[b] = true;
            scarecrows.add(f);
        }
        boolean[] pocket = new boolean[256];
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int lz = 1; lz <= 14; lz++)
            for (int lx = 1; lx <= 14; lx++) {
                int i = ChunkMask.idx(lx, lz);
                Field f = cols.field[i];
                if (f == null || !ok[i] || reserved[i] || !f.pocketCell(bx + lx, bz + lz)) continue;
                boolean fine = true;
                for (int[] d : n4) {
                    int j = ChunkMask.idx(lx + d[0], lz + d[1]);
                    fine &= cols.field[j] != null && ok[j] && g[j] >= g[i];
                }
                pocket[i] = fine;
            }
        BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
        for (int i = 0; i < 256; i++) {
            Field f = cols.field[i];
            if (f == null || !ok[i]) continue;
            int x = bx + (i & 15), z = bz + (i >> 4), y = g[i];
            if (pocket[i]) {
                set(cv, pos, x, y, z, Blocks.WATER.defaultBlockState());
                put(cv, pos, x, y + 1, z, f.lily(x, z) ? Blocks.LILY_PAD.defaultBlockState() : AIR);
            } else {
                set(cv, pos, x, y, z, farmland);
                put(cv, pos, x, y + 1, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, f.wheatAge(x, z)));
            }
        }
        for (Field f : scarecrows) {
            int y = g[ChunkMask.idx(f.sx & 15, f.sz & 15)];
            set(cv, pos, f.sx, y, f.sz, Blocks.COARSE_DIRT.defaultBlockState());
            BlockState fence = Palette.wood(Palette.wood(cv.biome(f.sx, y + 1, f.sz))).fence().defaultBlockState();
            set(cv, pos, f.sx, y + 1, f.sz, fence);
            set(cv, pos, f.sx, y + 2, f.sz, Blocks.HAY_BLOCK.defaultBlockState());
            for (int s = -1; s <= 1; s += 2) {
                int ax = f.sx + s * f.armX, az = f.sz + s * f.armZ;
                put(cv, pos, ax, y + 1, az, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 1));
                set(cv, pos, ax, y + 2, az, fence);
            }
            set(cv, pos, f.sx, y + 3, f.sz, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, dir(f.faceX, f.faceZ)));
            set(cv, pos, f.sx, y + 4, f.sz, Blocks.CARPET.brown().defaultBlockState());
        }
        for (int i = 0; i < 256; i++) {
            int x = bx + (i & 15), z = bz + (i >> 4);
            Field h = cols.head[i];
            if (h != null) {
                int kind = h.headland(x, z);
                if (kind == 0) continue;
                int y = groundTop(cv, pos, x, z, h.y);
                if (y == NONE || !get(cv, pos, x, y, z).is(Blocks.GRASS_BLOCK) || !get(cv, pos, x, y + 1, z).isAir() && !get(cv, pos, x, y + 1, z).is(Blocks.SHORT_GRASS)) continue;
                set(cv, pos, x, y + 1, z, (kind == 1 ? Blocks.SHORT_GRASS : kind == 2 ? Blocks.POPPY : Blocks.OXEYE_DAISY).defaultBlockState());
                continue;
            }
            if (cols.field[i] != null || m.nofield[i] || propCols != null && propCols[i]) continue;
            float in = m.in[i];
            if (in != Float.NEGATIVE_INFINITY && (in >= -6.0f || m.kind[i] != EdgePlan.GROUND || m.conn[i])) continue;
            for (Field f : m.fields) {
                boolean fence = f.fenceAt(x, z);
                int hedge = fence ? 0 : f.hedgeAt(x, z);
                if (!fence && hedge == 0) continue;
                int y = groundTop(cv, pos, x, z, f.y);
                if (y == NONE || !soil(get(cv, pos, x, y, z)) || !clearable(get(cv, pos, x, y + 1, z))) break;
                boolean tree = false;
                for (int k = y + 1; k <= y + 3; k++) tree |= woodyOrLeaves(get(cv, pos, x, k, z));
                if (tree) break;
                int wood = Palette.wood(cv.biome(x, y + 1, z));
                if (fence) put(cv, pos, x, y + 1, z, Palette.wood(wood).fence().defaultBlockState());
                else {
                    put(cv, pos, x, y + 1, z, leaves(wood));
                    if (hedge == 2 && clearable(get(cv, pos, x, y + 2, z))) put(cv, pos, x, y + 2, z, leaves(wood));
                }
                break;
            }
        }
    }
}
