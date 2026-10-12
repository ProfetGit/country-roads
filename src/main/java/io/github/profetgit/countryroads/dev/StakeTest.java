package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.HousePaths;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.path.StakeBlockEntity;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Headless house-path test: put a stake on the ground at (x, z), survey, build (free, no player), then take it up and
 * compare the area with how it was. Lines: CR-STAKE built=.. journal=.. restored=PASS|FAIL.
 */
final class StakeTest {
    private StakeTest() {}

    static int run(CommandSourceStack src, int x, int z, boolean keep) {
        ServerLevel level = src.getLevel();
        int r = 170, cr = (r >> 4) + 1;
        for (int dx = -cr; dx <= cr; dx++)
            for (int dz = -cr; dz <= cr; dz++) level.setChunkForced((x >> 4) + dx, (z >> 4) + dz, true);
        int[] phase = {-1};
        int[] built = new int[2];
        BlockPos[] stakeRef = new BlockPos[1];
        int[][] before = new int[1][];
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>[] beforeStates = new java.util.Map[1];
        @SuppressWarnings("unchecked")
        java.util.List<HousePaths.Cell>[] cells = new java.util.List[1];
        DevCommands.queue(() -> {
            if (phase[0] == -1) {
                for (int dx = -cr; dx <= cr; dx++)
                    for (int dz = -cr; dz <= cr; dz++)
                        if (level.getChunkSource().getChunkNow((x >> 4) + dx, (z >> 4) + dz) == null) return false;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                stakeRef[0] = new BlockPos(x, y, z);
                level.setBlock(stakeRef[0], Stake.BLOCK.defaultBlockState(), 3);
                HousePaths.survey(level, stakeRef[0], null);
                phase[0] = 0;
                return false;
            }
            BlockPos stake = stakeRef[0];
            if (HousePaths.busy()) return false;
            StakeBlockEntity be = (StakeBlockEntity) level.getBlockEntity(stake);
            switch (phase[0]++) {
                case 0 -> {
                    if (be == null || be.status != StakeBlockEntity.PREVIEW) {
                        CountryRoads.LOG.info("CR-STAKE FAIL no survey (status " + (be == null ? "?" : be.status) + ")");
                        return true;
                    }
                    built[0] = be.survey.cells().size();
                    cells[0] = be.survey.cells();
                    before[0] = snapshot(level, cells[0]);
                    beforeStates[0] = states(level, cells[0]);
                    HousePaths.use(level, stake, null);
                    return false;
                }
                case 1 -> {
                    built[1] = be.journal.size();
                    if (keep) {
                        // leave it built and print a camera shot: behind the stake, looking down the path
                        var cs = cells[0];
                        var far = cs.get(Math.min(cs.size() - 1, 14));
                        double dx = far.x() - stake.getX(), dz = far.z() - stake.getZ(), l = Math.max(1, Math.hypot(dx, dz));
                        double cx = stake.getX() + 0.5 - dx / l * 6, cz = stake.getZ() + 0.5 - dz / l * 6, cy = stake.getY() + 4;
                        double lx = far.x() + 0.5 - cx, ly = far.y() + 1 - cy, lz = far.z() + 0.5 - cz;
                        CountryRoads.LOG.info(String.format(java.util.Locale.ROOT, "CR-SHOT %.1f %.1f %.1f %.1f %.1f housepath", cx, cy, cz,
                            Math.toDegrees(Math.atan2(-lx, lz)), -Math.toDegrees(Math.atan2(ly, Math.hypot(lx, lz)))));
                        CountryRoads.LOG.info("CR-STAKE KEPT cells=" + built[0] + " journal=" + built[1]);
                        return true;
                    }
                    int[] mid = snapshot(level, cells[0]);
                    int changed = diff(before[0], mid);
                    HousePaths.takeUp(level, stake);
                    int[] after = snapshot(level, cells[0]);
                    int left = diff(before[0], after);
                    if (left > 0) explain(level, cells[0], beforeStates[0]);
                    level.setBlock(stake, Blocks.AIR.defaultBlockState(), 3);
                    CountryRoads.LOG.info("CR-STAKE " + (built[0] > 0 && changed > 0 && left == 0 ? "PASS" : "FAIL") + " cells=" + built[0]
                        + " journal=" + built[1] + " changedColumns=" + changed + " notRestored=" + left);
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
        return 1;
    }

    /** One number per path cell and its 8 neighbours: hash of the blocks from 3 below to 3 above the planned surface. */
    private static int[] snapshot(ServerLevel level, java.util.List<HousePaths.Cell> cells) {
        int[] out = new int[cells.size() * 9];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int k = 0;
        for (HousePaths.Cell c : cells)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int h = 0, base = (int) Math.floor(c.y());
                    for (int y = base - 3; y <= base + 3; y++) h = h * 31 + level.getBlockState(p.set(c.x() + dx, y, c.z() + dz)).hashCode();
                    out[k++] = h;
                }
        return out;
    }

    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states(ServerLevel level, java.util.List<HousePaths.Cell> cells) {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> m = new java.util.HashMap<>();
        for (HousePaths.Cell c : cells)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int y = (int) Math.floor(c.y()) - 3; y <= (int) Math.floor(c.y()) + 3; y++) {
                        BlockPos p = new BlockPos(c.x() + dx, y, c.z() + dz);
                        m.put(p, level.getBlockState(p));
                    }
        return m;
    }

    private static void explain(ServerLevel level, java.util.List<HousePaths.Cell> cells, java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before) {
        int shown = 0;
        for (var e : before.entrySet()) {
            var now = level.getBlockState(e.getKey());
            if (now != e.getValue() && shown++ < 8) CountryRoads.LOG.info("CR-STAKE-DIFF " + e.getKey().toShortString() + " was " + e.getValue() + " now " + now);
        }
    }

    private static int diff(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }
}
