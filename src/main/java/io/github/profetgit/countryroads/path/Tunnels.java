package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.paint.Palette;
import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tunnels of a house path: a 3 wide, 3 high bore through rock at the walked level. Rough natural walls (the rock is left
 * as it is), a timber frame every FRAME_EVERY cells, cobblestone portals where the roof begins and ends, a few wall
 * torches (the player pays them: anything placed with item value could be mined and farmed), moss on the floor.
 */
final class Tunnels {
    private Tunnels() {}

    /** Longest bore, columns the ground must stand above the walked level to roof it, shortest roofed run worth boring, and cells between frames. */
    static final int MAX_LEN = 48, ROOF = 4, MIN_ROOFED = 6, FRAME_EVERY = 8;
    private static final int[] DX = HousePaths.DX, DZ = HousePaths.DZ;

    /** Rock that is safe to bore: no fluid within a block of the walls and roof, nothing built, no trees, no road. */
    static boolean safe(ServerLevel level, int x, int z, int d, int y, int len) {
        if (y - 2 <= level.getMinY() + 4) return false;
        int rx = -DZ[d], rz = DX[d];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int m = 1; m < len; m++) {
            int cx = x + DX[d] * m, cz = z + DZ[d] * m;
            if (RoadColumns.isRoad(level, cx, cz)) return false;
            for (int l = -2; l <= 2; l++) {
                SurveyStats.touch(level, cx + l * rx, cz + l * rz);
                for (int yy = y - 1; yy <= y + 5; yy++) {
                    BlockState s = level.getBlockState(p.set(cx + l * rx, yy, cz + l * rz));
                    if (!s.getFluidState().isEmpty() || Retrofit.isCrafted(s)) return false;
                    if (Math.abs(l) <= 1 && yy > y && yy <= y + ROOF && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES))) return false;
                }
            }
        }
        return true;
    }

    /**
     * A frame or portal block goes in unless the column is itself part of the path (another stretch or a bend can pass
     * close by), or a tree stands there: logs are never cut (a portal post once replaced the trunk beside it).
     */
    private static void put(ServerLevel level, PathData be, BlockPos pos, BlockState state, java.util.Set<Long> cols) {
        if (cols.contains(net.minecraft.world.level.ChunkPos.pack(pos.getX(), pos.getZ()))) return;
        if (level.getBlockState(pos).is(BlockTags.LOGS)) return;
        HousePaths.change(level, be, pos, state);
    }

    static boolean isFrame(HousePaths.Cell c) {
        return c.tun() == 1 && !c.edge() && c.tidx() % FRAME_EVERY == 4;
    }

    /** One tunnel cell: floor, the 3x3 inside, the roof, and on the centre cells the frames and portals. */
    static void carve(ServerLevel level, PathData be, HousePaths.Cell c, java.util.Set<Long> cols) {
        int y = c.y();
        BlockPos at = new BlockPos(c.x(), y, c.z());
        long h = c.x() * 0x2545F4914F6CDD1DL ^ c.z() * 0x9E3779B97F4A7C15L;
        var biome = level.getBiome(at);
        BlockState floor = c.stair() >= 0 ? Palette.houseStep(biome, h, false, true).setValue(StairBlock.FACING, HousePaths.DIRS[c.stair()])
            : c.slab() ? Palette.houseStep(biome, h, true, true)
            : Palette.tunnelFloor(biome, h, c.edge());
        BlockPos under = at.below();
        if (!PaintRules.solid(level.getBlockState(under))) HousePaths.change(level, be, under, Blocks.STONE.defaultBlockState());
        HousePaths.change(level, be, at, floor);
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos q = at.above(dy);
            BlockState s = level.getBlockState(q);
            if (!s.isAir() && !Retrofit.isCrafted(s) && !s.is(BlockTags.LOGS)) HousePaths.change(level, be, q, Blocks.AIR.defaultBlockState());
        }
        BlockPos roof = at.above(HousePaths.TUNNEL_ROOF);
        if (!PaintRules.solid(level.getBlockState(roof))) HousePaths.change(level, be, roof, Blocks.STONE.defaultBlockState());
        if (c.edge()) {
            if (c.stair() < 0 && !c.slab() && ((h >>> 20) & 7) == 0 && level.getBlockState(at.above()).isAir()) HousePaths.change(level, be, at.above(), Blocks.MOSS_CARPET.defaultBlockState());
            return;
        }
        int rx = -DZ[c.dir()], rz = DX[c.dir()];
        if (isFrame(c)) {
            var log = Palette.woodLog(biome);
            var planks = Palette.woodPlanks(biome);
            for (int s = -2; s <= 2; s += 4) {
                for (int dy = 1; dy <= 3; dy++) put(level, be, at.offset(s * rx, dy, s * rz), log, cols);
            }
            for (int l = -2; l <= 2; l++) put(level, be, at.offset(l * rx, HousePaths.TUNNEL_ROOF, l * rz), planks, cols);
            int side = ((c.tidx() / FRAME_EVERY) & 1) == 0 ? 1 : -1;
            BlockPos torch = at.offset(side * rx, 2, side * rz);
            if (level.getBlockState(torch).isAir() && level.getBlockState(at.offset(2 * side * rx, 2, 2 * side * rz)).isSolid()) {
                Direction toCentre = null;
                for (Direction dd : HousePaths.DIRS) if (dd.getStepX() == -side * rx && dd.getStepZ() == -side * rz) toCentre = dd;
                if (toCentre != null) {
                    HousePaths.change(level, be, torch, Blocks.WALL_TORCH.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, toCentre));
                    be.torchPos.add(torch.asLong());
                }
            }
        } else if (c.tun() == 2) {
            for (int l = -2; l <= 2; l++) put(level, be, at.offset(l * rx, HousePaths.TUNNEL_ROOF, l * rz), Palette.portalStone(h + l * 31), cols);
            for (int s = -2; s <= 2; s += 4)
                for (int dy = 0; dy <= 3; dy++) put(level, be, at.offset(s * rx, dy, s * rz), Palette.portalStone(h + s * 17 + dy), cols);
        }
    }
}
