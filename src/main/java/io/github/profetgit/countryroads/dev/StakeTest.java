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
                    walk(level, cells[0], stake);
                    if (keep) {
                        // leave it built and print a camera shot: behind the stake, looking down the path
                        var cs = cells[0];
                        var far = cs.get(Math.min(cs.size() - 1, 14));
                        int bestN = -1;
                        for (int i = 4; i < cs.size(); i++) {
                            int cnt = 0;
                            for (int k = Math.max(0, i - 5); k <= Math.min(cs.size() - 1, i + 5); k++) if (cs.get(k).stair() >= 0 && !cs.get(k).edge()) cnt++;
                            if (cnt > bestN && !cs.get(i).edge()) {
                                bestN = cnt;
                                far = cs.get(i);
                            }
                        }
                        var prevCell = cs.get(Math.max(0, cs.indexOf(far) - 6));
                        double dx = far.x() - prevCell.x(), dz = far.z() - prevCell.z(), l = Math.max(1, Math.hypot(dx, dz));
                        double cx = far.x() + 0.5 - dx / l * 7, cz = far.z() + 0.5 - dz / l * 7, cy = far.y() + 5;
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

    /**
     * Walks the built path: centre cells must touch along a side, differ by at most one block, every rise must be a
     * stair facing up the slope in its upper cell, and two blocks of headroom must be free. Prints CR-STAKE-WALK.
     */
    private static void walk(ServerLevel level, java.util.List<HousePaths.Cell> cells, BlockPos stake) {
        int bad = 0, stairs = 0, steps = 0, flat = 0;
        HousePaths.Cell prev = null;
        for (HousePaths.Cell c : cells) {
            BlockPos at = new BlockPos(c.x(), c.y(), c.z());
            var state = level.getBlockState(at);
            boolean isStair = state.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            if (isStair) stairs++;
            if (isStair != (c.stair() >= 0) && !c.water()) bad = fail(++bad, "stair mismatch", at, state);
            if (isStair && state.getValue(net.minecraft.world.level.block.StairBlock.FACING) != HousePaths.DIRS[c.stair()]) bad = fail(++bad, "stair facing", at, state);
            if (!isStair && !state.isSolid() && !c.water()) bad = fail(++bad, "surface not solid", at, state);
            for (int dy = 1; dy <= 2; dy++)
                if (!level.getBlockState(at.above(dy)).getCollisionShape(level, at.above(dy)).isEmpty()) bad = fail(++bad, "headroom", at.above(dy), level.getBlockState(at.above(dy)));
            if (!c.edge()) {
                if (prev != null) {
                    int dy = c.y() - prev.y(), man = Math.abs(c.x() - prev.x()) + Math.abs(c.z() - prev.z());
                    if (man != 1) bad = fail(++bad, "cells do not touch", at, state);
                    if (Math.abs(dy) > 1) bad = fail(++bad, "step over one block", at, state);
                    if (dy == 0) flat++;
                    else steps++;
                    var upper = dy > 0 ? c : prev;
                    if (Math.abs(dy) == 1 && upper.stair() < 0 && !upper.water()) bad = fail(++bad, "step without stair", new BlockPos(upper.x(), upper.y(), upper.z()), state);
                }
                prev = c;
            }
        }
        CountryRoads.LOG.info("CR-STAKE-WALK " + (bad == 0 ? "PASS" : "FAIL") + " cells=" + cells.size() + " flat=" + flat + " steps=" + steps + " stairs=" + stairs + " bad=" + bad
            + " palette=" + io.github.profetgit.countryroads.paint.Palette.surface(level.getBiome(stake)).name() + " at=" + stake.toShortString());
    }

    private static int fail(int n, String what, BlockPos at, net.minecraft.world.level.block.state.BlockState s) {
        if (n <= 8) CountryRoads.LOG.info("CR-STAKE-WALK-BAD " + what + " at " + at.toShortString() + " " + s);
        return n;
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
