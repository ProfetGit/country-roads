package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.HousePaths;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.path.PathData;
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

    /** Blocks force-loaded around the stake before the survey: the default, warm (the whole survey window) and cold (only the stake's chunks). */
    static final int PRELOAD = 170, WARM = 330, COLD = 0;

    static int run(CommandSourceStack src, int x, int z, boolean keep) {
        return run(src, x, z, keep, Integer.MIN_VALUE, 0, PRELOAD);
    }

    static int run(CommandSourceStack src, int x, int z, boolean keep, int viaX, int viaZ) {
        return run(src, x, z, keep, viaX, viaZ, PRELOAD);
    }

    /** staketest ... door / doorkeep: start the path from a door with the stake item (and make it permanent after the build). */
    static boolean doorMode, commitMode;
    /** staketest ... house / houseyard / housewalls: a player's cabin around the door (1), plus a closed fence yard in front (2), plus a stone-brick wall ring (3). */
    static int houseMode;
    /** staketest X Z via VX VZ WX WZ: a second waypoint (the chain test). */
    static int[] via2;
    /** staketest X Z via VX VZ end: the last stake ends the path (sneak + right-click), joining no road. */
    static boolean endMode;

    /** A cabin north of the door (door in its south wall, outside = south): planks, roof, a stair doorstep, a porch roof on fence posts; mode 2 adds a closed fence yard, 3 a stone-brick ring. */
    static void cabin(ServerLevel level, BlockPos door, int mode) {
        int x = door.getX(), y = door.getY(), z = door.getZ();
        var planks = Blocks.OAK_PLANKS.defaultBlockState();
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -6; dz <= 0; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    boolean wall = Math.abs(dx) == 3 || dz == -6 || dz == 0;
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (dy == -1 || dy == 3) level.setBlock(p, planks, 3);
                    else level.setBlock(p, wall ? planks : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState(), 3);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        level.setBlock(door.south().below(), Blocks.OAK_STAIRS.defaultBlockState(), 3);
        level.setBlock(door.south(), Blocks.OAK_STAIRS.defaultBlockState(), 3);
        for (int dx : new int[]{-1, 1}) {
            for (int dy = 0; dy <= 2; dy++) level.setBlock(new BlockPos(x + dx, y + dy, z + 3), Blocks.OAK_FENCE.defaultBlockState(), 3);
        }
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 1; dz <= 3; dz++) level.setBlock(new BlockPos(x + dx, y + 3, z + dz), planks, 3);
        if (mode >= 2)
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = 6; dz <= 6; dz++) level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.OAK_FENCE.defaultBlockState(), 3);
        if (mode == 3 || mode == 4) {
            int rx = mode == 3 ? 7 : 10, rz = mode == 3 ? 7 : 10, rn = mode == 3 ? 9 : 12;
            for (int dx = -rx; dx <= rx; dx++)
                for (int dz = -rn; dz <= rz; dz++)
                    if (Math.abs(dx) == rx || dz == -rn || dz == rz)
                        for (int dy = -4; dy <= 8; dy++) level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
        }
    }

    /** Road decoration (fences, walls, lamps) is built too, but it belongs to the road: a road column within 3. */
    static boolean roadNear(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) if (io.github.profetgit.countryroads.world.RoadColumns.isRoad(level, x + dx, z + dz)) return true;
        return false;
    }

    static int run(CommandSourceStack src, int x, int z, boolean keep, int viaX, int viaZ, int preload) {
        final boolean door = doorMode, commitTest = commitMode;
        final int house = houseMode;
        houseMode = 0;
        final int[] secondVia = via2;
        final boolean endTest = endMode;
        via2 = null;
        endMode = false;
        doorMode = false;
        commitMode = false;
        ServerLevel level = src.getLevel();
        int r = preload, cr = (r >> 4) + 1;
        int[] phase = {-2}, settle = {0, 0};
        int[] built = new int[2];
        BlockPos[] stakeRef = new BlockPos[1], viaRef = new BlockPos[1], viaRef2 = new BlockPos[1];
        int[][] before = new int[1][];
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>[] beforeStates = new java.util.Map[1];
        @SuppressWarnings("unchecked")
        java.util.List<HousePaths.Cell>[] cells = new java.util.List[1];
        java.util.Map<Long, String>[] skips = new java.util.Map[1];
        java.util.List<io.github.profetgit.countryroads.path.BridgeKit.Bridge>[] bridgesRef = new java.util.List[1];
        DevCommands.queue(() -> {
            if (phase[0] == -2) {
                // tickets only once this test's turn comes: tests run one after another and may share chunks
                for (int dx = -cr; dx <= cr; dx++)
                    for (int dz = -cr; dz <= cr; dz++) hold(level, (x >> 4) + dx, (z >> 4) + dz, true);
                phase[0] = -1;
                return false;
            }
            if (phase[0] == -1) {
                for (int dx = -cr; dx <= cr; dx++)
                    for (int dz = -cr; dz <= cr; dz++)
                        if (level.getChunkSource().getChunkNow((x >> 4) + dx, (z >> 4) + dz) == null) return false;
                // let the server settle after the preload, so the survey is timed on a quiet server
                var server = level.getServer();
                long last = server.getTickTimesNanos()[server.getTickCount() % 100];
                settle[0] = last < 30_000_000L ? settle[0] + 1 : 0;
                if (settle[0] < 40 && ++settle[1] < 1200) return false;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                stakeRef[0] = new BlockPos(x, y, z);
                if (door) {
                    level.setBlock(stakeRef[0], Blocks.OAK_DOOR.defaultBlockState(), 3);
                    level.setBlock(stakeRef[0].above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
                    if (house > 0) cabin(level, stakeRef[0], house);
                    HousePaths.useDoor(level, stakeRef[0], null, net.minecraft.core.Direction.SOUTH);
                } else {
                    level.setBlock(stakeRef[0], Stake.BLOCK.defaultBlockState(), 3);
                    PathData fresh = HousePaths.data(level, stakeRef[0]);
                    if (fresh != null) {
                        fresh.ends = false;
                        fresh.waypoints.clear();
                    }
                    HousePaths.survey(level, stakeRef[0], null);
                }
                if (viaX != Integer.MIN_VALUE) {
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) hold(level, (viaX >> 4) + dx, (viaZ >> 4) + dz, true);
                    int vy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, viaX, viaZ);
                    BlockPos vp = new BlockPos(viaX, vy, viaZ);
                    level.setBlock(vp, Stake.BLOCK.defaultBlockState(), 3);
                    viaRef[0] = vp;
                    HousePaths.placed(level, vp, null);
                    if (secondVia != null) {
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++) hold(level, (secondVia[0] >> 4) + dx, (secondVia[1] >> 4) + dz, true);
                        int vy2 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, secondVia[0], secondVia[1]);
                        BlockPos vp2 = new BlockPos(secondVia[0], vy2, secondVia[1]);
                        level.setBlock(vp2, Stake.BLOCK.defaultBlockState(), 3);
                        viaRef2[0] = vp2;
                        HousePaths.placed(level, vp2, null);
                    }
                    if (endTest) HousePaths.toggleEnd(level, viaRef2[0] != null ? viaRef2[0] : viaRef[0], null);
                }
                phase[0] = 0;
                return false;
            }
            BlockPos stake = stakeRef[0];
            if (HousePaths.busy()) return false;
            PathData be = HousePaths.data(level, stake);
            if (phase[0] == 0) {
                var st = io.github.profetgit.countryroads.path.SurveyStats.last;
                if (st != null) {
                    CountryRoads.LOG.info(st.line() + " at=" + x + "," + z + " preload=" + preload);
                    synchronized (st.candLines) {
                        for (String cl : st.candLines) CountryRoads.LOG.info(cl);
                    }
                    CountryRoads.LOG.info("CR-STAKE-COST direct=" + st.costDirect + " final=" + st.costFinal + " length=" + (be != null && be.survey != null ? be.survey.length() : -1)
                        + " tunnelCells=" + (be != null && be.survey != null ? be.survey.tunnelCells() : -1));
                }
                for (int dx = -cr; dx <= cr; dx++)
                    for (int dz = -cr; dz <= cr; dz++) hold(level, (x >> 4) + dx, (z >> 4) + dz, false);
            }
            switch (phase[0]++) {
                case 0 -> {
                    if (be == null || be.status != PathData.PREVIEW) {
                        CountryRoads.LOG.info("CR-STAKE FAIL no survey (status " + (be == null ? "?" : be.status) + ")");
                        return true;
                    }
                    built[0] = be.survey.cells().size();
                    cells[0] = be.survey.cells();
                    {
                        // the margin: past the first cells (the start may sit inside it), no centre cell within 2 columns of a built top block
                        int near = 0, minD = 99, checked = 0, nearAfterStart = 0;
                        int[] shown = {0};
                        for (int ci = 0; ci < cells[0].size(); ci++) {
                            var cc = cells[0].get(ci);
                            if (cc.edge() || cc.bank() || cc.rail()) continue;
                            int dmin = 99;
                            for (int dx = -3; dx <= 3; dx++)
                                for (int dz = -3; dz <= 3; dz++) {
                                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cc.x() + dx, cc.z() + dz) - 1;
                                    var tb = level.getBlockState(new BlockPos(cc.x() + dx, top, cc.z() + dz));
                                    if (io.github.profetgit.countryroads.retro.Retrofit.isCrafted(tb) && !roadNear(level, cc.x() + dx, cc.z() + dz)) {
                                        int dd = Math.max(Math.abs(dx), Math.abs(dz));
                                        if (dd <= 2 && ci >= 12 && shown[0]++ < 3)
                                            CountryRoads.LOG.info("CR-STAKE-MARGIN-NEAR cell " + ci + " at " + cc.x() + "," + cc.z() + " d=" + dd + " " + tb.getBlock().getDescriptionId() + " at " + (cc.x() + dx) + "," + (cc.z() + dz));
                                        dmin = Math.min(dmin, dd);
                                    }
                                }
                            if (ci >= 12) {
                                checked++;
                                minD = Math.min(minD, dmin);
                                if (dmin <= 2) nearAfterStart++;
                            }
                            if (dmin <= 2) near++;
                        }
                        CountryRoads.LOG.info("CR-STAKE-MARGIN " + (nearAfterStart == 0 ? "PASS" : "FAIL") + " centreCellsWithin2=" + near + " pastStart=" + nearAfterStart + " minDist=" + minD + " checked=" + checked);
                    }
                    if (secondVia != null) {
                        var wps = be.waypoints;
                        int[] at = new int[wps.size()];
                        int prev = -1;
                        boolean inOrder = wps.size() == 2;
                        for (int wi = 0; wi < wps.size(); wi++) {
                            at[wi] = -1;
                            for (int ci = 0; ci < cells[0].size(); ci++) {
                                var cc = cells[0].get(ci);
                                if (!cc.edge() && cc.x() == wps.get(wi).getX() && cc.z() == wps.get(wi).getZ()) {
                                    at[wi] = ci;
                                    break;
                                }
                            }
                            if (at[wi] <= prev) inOrder = false;
                            prev = at[wi];
                        }
                        int[] legs = HousePaths.legsOf(cells[0], wps);
                        PathData wd = HousePaths.data(level, viaRef2[0]);
                        var enc = PathData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, wd).result();
                        var dec = enc.flatMap(t -> PathData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, t).result());
                        var enc0 = PathData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, be).result();
                        var dec0 = enc0.flatMap(t -> PathData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, t).result());
                        boolean saved = dec.isPresent() && dec.get().role == PathData.WAYPOINT && stake.equals(dec.get().door)
                            && dec0.isPresent() && dec0.get().waypoints.equals(be.waypoints);
                        CountryRoads.LOG.info("CR-STAKE-CHAIN " + (inOrder && legs[legs.length - 1] == 2 && saved ? "PASS" : "FAIL") + " waypoints=" + wps.size() + " at=" + java.util.Arrays.toString(at)
                            + " lastLeg=" + legs[legs.length - 1] + " roles=" + wd.role + " savedRoundTrip=" + saved + " cells=" + cells[0].size());
                    }
                    skips[0] = be.survey.skipped();
                    bridgesRef[0] = be.survey.bridges();
                    before[0] = snapshot(level, cells[0]);
                    beforeStates[0] = states(level, cells[0]);
                    HousePaths.use(level, stake, null);
                    return false;
                }
                case 1 -> {
                    built[1] = be.journal.size();
                    walk(level, cells[0], stake, skips[0]);
                    map(level, cells[0]);
                    sim(level, cells[0]);
                    if (commitTest) {
                        int journalBefore = be.journal.size();
                        var enc = PathData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, be).result();
                        var dec = enc.flatMap(t -> PathData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, t).result());
                        boolean codecOk = dec.isPresent() && dec.get().journal.size() == journalBefore && dec.get().status == be.status && dec.get().builtAt == be.builtAt
                            && dec.get().columns.size() == be.columns.size() && dec.get().journal.get(0).laid() != null;
                        CountryRoads.LOG.info("CR-STAKE-CODEC " + (codecOk ? "PASS" : "FAIL") + " journal=" + journalBefore + " builtAt=" + be.builtAt);
                        boolean ok = HousePaths.commit(level, stake);
                        PathData after = HousePaths.data(level, stake);
                        boolean gone = door ? after == null : !level.getBlockState(stake).is(Stake.BLOCK);
                        boolean second = HousePaths.commit(level, stake);
                        CountryRoads.LOG.info("CR-STAKE-COMMIT " + (ok && gone && !second && journalBefore > 0 ? "PASS" : "FAIL") + " door=" + door + " committed=" + ok + " anchorGone=" + gone
                            + " secondCommit=" + second + " journalBefore=" + journalBefore + " journalAfter=" + be.journal.size());
                        return true;
                    }
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
                        HousePaths.Cell portal = null, inner = null;
                        for (var c : cs) {
                            if (c.edge()) continue;
                            if (portal == null && c.tun() == 2) portal = c;
                            if (portal != null && inner == null && c.tun() == 1 && c.tidx() >= 5) inner = c;
                        }
                        if (portal != null) {
                            int pdx = HousePaths.DIRS[portal.dir()].getStepX(), pdz = HousePaths.DIRS[portal.dir()].getStepZ();
                            shot(portal.x() + 0.5 - pdx * 8, portal.y() + 4.5, portal.z() + 0.5 - pdz * 8, portal.x() + 0.5, portal.y() + 2, portal.z() + 0.5, "tunnelA");
                            if (inner != null)
                                shot(inner.x() + 0.5 - pdx * 0.5, inner.y() + 2.62, inner.z() + 0.5 - pdz * 0.5, inner.x() + 0.5 + pdx * 10, inner.y() + 2, inner.z() + 0.5 + pdz * 10, "tunnelB");
                        }
                        shot(cx, cy, cz, far.x() + 0.5, far.y() + 1, far.z() + 0.5, "housepath");
                        shot(far.x() + 0.5, far.y() + 45, far.z() + 0.51, far.x() + 0.5, far.y(), far.z() + 0.5, "hptop");
                        // the middle of the longest bridge, from the side
                        HousePaths.Cell br = null;
                        int brLen = 0, run = 0;
                        for (int i = 0; i < cs.size(); i++) {
                            var q = cs.get(i);
                            if (q.edge()) continue;
                            run = q.water() || q.kit() ? run + 1 : 0;
                            if (run > brLen) {
                                brLen = run;
                                br = q;
                            }
                        }
                        if (br != null && brLen >= 3) {
                            int bi = cs.indexOf(br), mid = bi;
                            for (int k = bi, m = 0; k >= 0 && m < brLen / 2; k--) if (!cs.get(k).edge()) { mid = k; m++; }
                            var mc = cs.get(mid);
                            int rx = -HousePaths.DIRS[mc.dir()].getStepZ(), rz = HousePaths.DIRS[mc.dir()].getStepX();
                            int ax = HousePaths.DIRS[mc.dir()].getStepX(), az = HousePaths.DIRS[mc.dir()].getStepZ();
                            shot(mc.x() + 0.5 + rx * 9 - ax * 5, mc.y() + 5, mc.z() + 0.5 + rz * 9 - az * 5, mc.x() + 0.5, mc.y() + 0.5, mc.z() + 0.5, "hpbridge");
                            shot(mc.x() + 0.5 - ax * 9, mc.y() + 3, mc.z() + 0.5 - az * 9, mc.x() + 0.5, mc.y() + 1, mc.z() + 0.5, "hpbridgeB");
                        }
                        // and where the fieldstone steps (rocky ground) are densest
                        HousePaths.Cell rk = null;
                        int rockN = 0;
                        for (int i = 6; i < cs.size(); i++) {
                            if (cs.get(i).edge()) continue;
                            int cnt = 0;
                            for (int k = Math.max(0, i - 10); k <= Math.min(cs.size() - 1, i + 10); k++) {
                                var q = cs.get(k);
                                var st = level.getBlockState(new BlockPos(q.x(), q.y(), q.z()));
                                if ((q.slab() || q.stair() >= 0) && !q.water() && !st.is(net.minecraft.world.level.block.Blocks.MUD_BRICK_SLAB) && !st.is(net.minecraft.world.level.block.Blocks.MUD_BRICK_STAIRS)) cnt++;
                            }
                            if (cnt > rockN) {
                                rockN = cnt;
                                rk = cs.get(i);
                            }
                        }
                        if (rk != null) {
                            var back = cs.get(Math.max(0, cs.indexOf(rk) - 30));
                            double bx = rk.x() - back.x(), bz = rk.z() - back.z(), bl = Math.max(1, Math.hypot(bx, bz));
                            shot(rk.x() + 0.5 - bx / bl * 8, rk.y() + 6, rk.z() + 0.5 - bz / bl * 8, rk.x() + 0.5, rk.y() + 1, rk.z() + 0.5, "hprock");
                        }
                        // and where the half steps are densest, from behind at walking height
                        HousePaths.Cell sl = null;
                        int slabN = 0;
                        for (int i = 6; i < cs.size(); i++) {
                            if (cs.get(i).edge()) continue;
                            int cnt = 0;
                            for (int k = Math.max(0, i - 8); k <= Math.min(cs.size() - 1, i + 8); k++) if (cs.get(k).slab() && !cs.get(k).edge()) cnt++;
                            if (cnt > slabN) {
                                slabN = cnt;
                                sl = cs.get(i);
                            }
                        }
                        if (sl != null) {
                            var back = cs.get(Math.max(0, cs.indexOf(sl) - 30));
                            double bx = sl.x() - back.x(), bz = sl.z() - back.z(), bl = Math.max(1, Math.hypot(bx, bz));
                            shot(sl.x() + 0.5 - bx / bl * 9, sl.y() + 4, sl.z() + 0.5 - bz / bl * 9, sl.x() + 0.5, sl.y() + 1, sl.z() + 0.5, "hpslab");
                        }
                        var ahead = cs.get(Math.min(cs.size() - 1, cs.indexOf(far) + 9));
                        shot(ahead.x() + 0.5, ahead.y() + 5, ahead.z() + 0.5, far.x() + 0.5, far.y() + 1, far.z() + 0.5, "hpback");
                        CountryRoads.LOG.info("CR-STAKE KEPT cells=" + built[0] + " journal=" + built[1]);
                        return true;
                    }
                    // trees standing on a kit bridge are taken out whole on purpose (user, 2026-10-14); any other log is a cut
                    int logsCut = 0, leavesCut = 0, bridgeLogs = 0;
                    for (var e : be.journal) {
                        if (e.state().is(net.minecraft.tags.BlockTags.LOGS)) {
                            if (nearBridge(bridgesRef[0], e.pos())) bridgeLogs++;
                            else logsCut++;
                        } else if (e.state().is(net.minecraft.tags.BlockTags.LEAVES)) leavesCut++;
                    }
                    CountryRoads.LOG.info("CR-STAKE-TREES logs removed " + logsCut + " (must be 0), leaves removed " + leavesCut + ", bridge trees' logs " + bridgeLogs);
                    int shownLogs = 0;
                    for (var e : be.journal) {
                        if (!e.state().is(net.minecraft.tags.BlockTags.LOGS) || shownLogs++ >= 4) continue;
                        String what = "no cell";
                        for (var c : cells[0])
                            if (c.x() == e.pos().getX() && c.z() == e.pos().getZ())
                                what = "cell y=" + c.y() + (c.edge() ? " edge" : " centre") + (c.fringe() ? " fringe" : "") + " stair=" + c.stair() + " tun=" + c.tun() + " water=" + c.water();
                        CountryRoads.LOG.info("CR-STAKE-LOGCUT " + e.pos().toShortString() + " was " + e.state().getBlock().getDescriptionId().replace("block.minecraft.", "")
                            + " now " + level.getBlockState(e.pos()).getBlock().getDescriptionId().replace("block.minecraft.", "") + " " + what);
                    }
                    if (viaRef[0] != null) {
                        boolean onRoute = false;
                        for (var c : cells[0]) if (!c.edge() && c.x() == viaRef[0].getX() && c.z() == viaRef[0].getZ()) onRoute = true;
                        if (endTest) {
                            HousePaths.Cell last = null;
                            for (var c : cells[0]) if (!c.edge() && !c.bank()) last = c;
                            BlockPos endAt = viaRef2[0] != null ? viaRef2[0] : viaRef[0];
                            boolean atEnd = last != null && last.x() == endAt.getX() && last.z() == endAt.getZ();
                            CountryRoads.LOG.info("CR-STAKE-FREE " + (atEnd && be.ends ? "PASS" : "FAIL") + " endsAtStake=" + atEnd + " ends=" + be.ends + " last="
                                + (last == null ? "-" : last.x() + "," + last.z() + " y=" + last.y()) + " stake=" + endAt.toShortString() + " cells=" + cells[0].size());
                        }
                        CountryRoads.LOG.info("CR-STAKE-WAYPOINT " + (onRoute ? "PASS" : "FAIL") + " onRoute=" + onRoute + " stakeRemoved=" + !level.getBlockState(viaRef[0]).is(Stake.BLOCK));
                    }
                    {
                        int mud = 0, tracked = 0, blocked = 0, dropped = 0, normalOk = 0, normalBad = 0;
                        //? if fabric {
                        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(level);
                        //?} else if neoforge {
                        /*var fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
                        *///?} else {
                        /*var fake = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                            new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("countryroads:test".getBytes()), "[CR test]"),
                            net.minecraft.server.level.ClientInformation.createDefault());
                        *///?}
                        var pb = io.github.profetgit.countryroads.path.PathBlocks.of(level);
                        for (var e : be.journal) {
                            var cur = level.getBlockState(e.pos());
                            if (cur.is(Blocks.PACKED_MUD) || cur.is(Blocks.MUD)) mud++;
                        }
                        for (var e : new java.util.ArrayList<>(be.journal)) {
                            var cur = level.getBlockState(e.pos());
                            if (cur.isAir()) continue;
                            boolean free = cur.is(Blocks.DIRT) || cur.is(Blocks.DIRT_PATH) || cur.is(Blocks.COARSE_DIRT) || cur.is(Blocks.ROOTED_DIRT) || cur.is(Blocks.GRASS_BLOCK);
                            if (free) {
                                if (normalOk + normalBad < 20) {
                                    boolean okb = io.github.profetgit.countryroads.path.PathBlocks.beforeBreak(level, fake, e.pos(), cur);
                                    if (okb && !level.getBlockState(e.pos()).isAir()) normalOk++; else normalBad++;
                                }
                                continue;
                            }
                            if (cur.getBlock() instanceof net.minecraft.world.level.block.BushBlock || cur.is(Blocks.TALL_GRASS) || cur.is(Blocks.SHORT_GRASS) || cur.getCollisionShape(level, e.pos()).isEmpty() && cur.getFluidState().isEmpty() && !cur.is(Blocks.LANTERN) && !cur.is(Blocks.WALL_TORCH)) continue;
                            tracked++;
                            if (blocked + dropped >= 40) continue;
                            boolean brk = io.github.profetgit.countryroads.path.PathBlocks.beforeBreak(level, fake, e.pos(), cur);
                            boolean items = !level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(e.pos()).inflate(2)).isEmpty();
                            if (!brk && level.getBlockState(e.pos()).isAir() && !items) blocked++;
                            else {
                                dropped++;
                                CountryRoads.LOG.info("CR-STAKE-NODROP-WHY " + e.pos().toShortString() + " " + cur.getBlock().getDescriptionId() + " cancelled=" + !brk + " air=" + level.getBlockState(e.pos()).isAir() + " items=" + items);
                            }
                        }
                        CountryRoads.LOG.info("CR-STAKE-NODROP " + (mud == 0 && dropped == 0 && normalBad == 0 && blocked > 0 ? "PASS" : "FAIL") + " mudPlaced=" + mud + " valuableTracked=" + tracked
                            + " brokenNoDrop=" + blocked + " dropped=" + dropped + " plainOk=" + normalOk + " plainBad=" + normalBad + " free=" + (be.paid.isEmpty()));
                    }
                    int[] mid = snapshot(level, cells[0]);
                    int changed = diff(before[0], mid);
                    BlockPos built0 = null;
                    net.minecraft.world.level.block.state.BlockState orig0 = null;
                    if (door) {
                        // a block the player built over the path afterwards must survive taking it up
                        for (var e : be.journal) {
                            var cur = level.getBlockState(e.pos());
                            if (e.laid() != null && !cur.isAir() && !cur.is(Blocks.OBSIDIAN) && cur.getBlock() == e.laid().getBlock() && cur.isSolid() && e.state().isSolid()) {
                                built0 = e.pos();
                                orig0 = e.state();
                                break;
                            }
                        }
                        if (built0 != null) level.setBlock(built0, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    }
                    HousePaths.takeUp(level, stake);
                    if (door) {
                        boolean kept = built0 != null && level.getBlockState(built0).is(Blocks.OBSIDIAN);
                        CountryRoads.LOG.info("CR-STAKE-UNDOSKIP " + (kept ? "PASS" : "FAIL") + " at=" + built0);
                        if (kept) level.setBlock(built0, orig0, 3);
                    }
                    // a waypoint stake is used up by the build (it comes back as an item): put it back for the comparison
                    if (viaRef[0] != null) level.setBlock(viaRef[0], Stake.BLOCK.defaultBlockState(), 3);
                    if (viaRef2[0] != null) level.setBlock(viaRef2[0], Stake.BLOCK.defaultBlockState(), 3);
                    int[] after = snapshot(level, cells[0]);
                    int left = diff(before[0], after);
                    if (left > 0) explain(level, cells[0], beforeStates[0]);
                    level.setBlock(stake, Blocks.AIR.defaultBlockState(), 3);
                    if (door) level.setBlock(stake.above(), Blocks.AIR.defaultBlockState(), 3);
                    if (viaRef[0] != null) level.setBlock(viaRef[0], Blocks.AIR.defaultBlockState(), 3);
                    if (viaRef2[0] != null) level.setBlock(viaRef2[0], Blocks.AIR.defaultBlockState(), 3);
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
     * Cancel: a survey is started (nothing preloaded, so it is fetching chunks) and the stake broken a few ticks in.
     * PASS when no survey is left, it held no tickets afterwards and no preview came. Multi: three stakes 48 blocks
     * apart survey at once; PASS when all three end with a preview.
     */
    static int special(CommandSourceStack src, int x, int z, String mode) {
        ServerLevel level = src.getLevel();
        int n = mode.equals("multi") ? 3 : 1;
        BlockPos[] stakes = new BlockPos[n];
        int[] age = {0}, broke = {0};
        DevCommands.queue(() -> {
            if (age[0] == 0) {
                for (int i = 0; i < n; i++) {
                    int sx = x + (i == 1 ? 48 : 0), sz = z + (i == 2 ? 48 : 0);
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) hold(level, (sx >> 4) + dx, (sz >> 4) + dz, true);
                }
            }
            age[0]++;
            if (stakes[0] == null) {
                for (int i = 0; i < n; i++) {
                    int sx = x + (i == 1 ? 48 : 0), sz = z + (i == 2 ? 48 : 0);
                    if (level.getChunkSource().getChunkNow(sx >> 4, sz >> 4) == null) return false;
                }
                for (int i = 0; i < n; i++) {
                    int sx = x + (i == 1 ? 48 : 0), sz = z + (i == 2 ? 48 : 0);
                    stakes[i] = new BlockPos(sx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz), sz);
                    level.setBlock(stakes[i], Stake.BLOCK.defaultBlockState(), 3);
                    HousePaths.survey(level, stakes[i], null);
                }
                age[0] = 1;
                return false;
            }
            if (mode.equals("cancel")) {
                if (broke[0] == 0 && (HousePaths.surveyTickets() > 0 || age[0] > 2400)) {
                    broke[0] = age[0];
                    int heldBefore = HousePaths.surveyTickets();
                    HousePaths.takeUp(level, stakes[0]);
                    level.setBlock(stakes[0], Blocks.AIR.defaultBlockState(), 3);
                    CountryRoads.LOG.info("CR-STAKE-CANCEL broke the stake while surveying (tickets held " + heldBefore + ")");
                }
                if (broke[0] == 0 || age[0] < broke[0] + 100) return false;
                boolean ok = !HousePaths.busy() && HousePaths.surveyTickets() == 0;
                CountryRoads.LOG.info("CR-STAKE-CANCEL " + (ok ? "PASS" : "FAIL") + " busy=" + HousePaths.busy() + " ticketsLeft=" + HousePaths.surveyTickets());
            } else {
                if (HousePaths.busy() && age[0] < 4000) return false;
                int previews = 0;
                for (BlockPos s : stakes)
                    { PathData be = HousePaths.data(level, s); if (be != null && be.status == PathData.PREVIEW) previews++; }
                CountryRoads.LOG.info("CR-STAKE-MULTI " + (previews == n ? "PASS" : "FAIL") + " previews=" + previews + "/" + n + " ticks=" + age[0]
                    + " ticketsLeft=" + HousePaths.surveyTickets());
                for (BlockPos s : stakes) level.setBlock(s, Blocks.AIR.defaultBlockState(), 3);
            }
            for (int i = 0; i < n; i++) {
                int sx = x + (i == 1 ? 48 : 0), sz = z + (i == 2 ? 48 : 0);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) hold(level, (sx >> 4) + dx, (sz >> 4) + dz, false);
            }
            return true;
        });
        return 1;
    }

    /** Keeps a chunk loaded (not ticking) for the test, or lets it go. */
    private static void hold(ServerLevel level, int cx, int cz, boolean on) {
        var pos = new net.minecraft.world.level.ChunkPos(cx, cz);
        if (on) level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, pos, 0);
        else level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, pos, 0);
    }

    /**
     * Moves a player-sized box (0.6 wide, 1.8 tall, step height 0.6) along the centre line of the built path, cell
     * centre to cell centre in 0.1 blocks, against the real collision shapes. Prints CR-STAKE-SIM and each place the
     * walker is stopped (a wall in its way, no room overhead, or ground lost).
     */
    private static void sim(ServerLevel level, java.util.List<HousePaths.Cell> cells) {
        java.util.List<HousePaths.Cell> line = new java.util.ArrayList<>();
        for (HousePaths.Cell c : cells) if (!c.edge()) line.add(c);
        double stand = line.get(0).y() + 1;
        int stops = 0;
        for (int i = 0; i + 1 < line.size() && stops < 12; i++) {
            HousePaths.Cell a = line.get(i), b = line.get(i + 1);
            for (int k = 1; k <= 10; k++) {
                double px = a.x() + 0.5 + (b.x() - a.x()) * k / 10.0, pz = a.z() + 0.5 + (b.z() - a.z()) * k / 10.0;
                double[] r = footprint(level, px, pz, stand);
                if (r[1] != 0) {
                    CountryRoads.LOG.info(String.format(java.util.Locale.ROOT, "CR-STAKE-SIM STOP cell %d (%d,%d,%d)->(%d,%d,%d) at %.1f,%.1f stand=%.2f %s", i, a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), px, pz, stand, r[1] == 1 ? "blocked" : "ground lost"));
                    stops++;
                    stand = Math.floor(stand) + 0;
                    if (r[1] == 1) CountryRoads.LOG.info("CR-STAKE-SIM BLOCKER " + lastBlocker);
                    for (int dz = -1; dz <= 1; dz++) {
                        StringBuilder sb = new StringBuilder("CR-STAKE-SIM SLICE z+" + dz + " x=" + a.x() + ".." + (b.x() + 1) + ":");
                        for (int yy = a.y() + 3; yy >= a.y() - 1; yy--) {
                            sb.append(" y").append(yy).append('=');
                            for (int xx = a.x() - 1; xx <= b.x() + 1; xx++) {
                                var st = level.getBlockState(new BlockPos(xx, yy, a.z() + dz));
                                sb.append(st.isAir() ? '.' : st.getBlock() instanceof net.minecraft.world.level.block.StairBlock ? "SNEW".charAt(st.getValue(net.minecraft.world.level.block.StairBlock.FACING).get2DDataValue()) : st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) ? 'G' : '#');
                            }
                        }
                        CountryRoads.LOG.info(sb.toString());
                    }
                    return;
                }
                stand = r[0];
            }
        }
        CountryRoads.LOG.info("CR-STAKE-SIM " + (stops == 0 ? "PASS" : "FAIL") + " cells=" + line.size());
    }

    private static String lastBlocker = "";

    /** {new stand height, 0 ok / 1 blocked / 2 ground lost} for a 0.6 x 1.8 box with its feet at stand, centred at (px, pz). */
    private static double[] footprint(ServerLevel level, double px, double pz, double stand) {
        double x0 = px - 0.3, x1 = px + 0.3, z0 = pz - 0.3, z1 = pz + 0.3, eps = 1e-7;
        java.util.List<double[]> boxes = new java.util.ArrayList<>();
        for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1 - eps); bx++)
            for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1 - eps); bz++)
                for (int by = (int) Math.floor(stand) - 5; by <= (int) Math.floor(stand) + 3; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    for (var bb : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                        double ax0 = bx + bb.minX, ax1 = bx + bb.maxX, az0 = bz + bb.minZ, az1 = bz + bb.maxZ;
                        if (ax1 > x0 + eps && ax0 < x1 - eps && az1 > z0 + eps && az0 < z1 - eps) boxes.add(new double[]{by + bb.minY, by + bb.maxY});
                    }
                }
        double top = stand;
        for (double[] b : boxes) if (b[1] > top + eps && b[1] <= stand + 0.6 + eps) top = Math.max(top, b[1]);
        for (double[] b : boxes) if (b[1] > top + eps && b[0] < top + 1.8 - eps) {
            lastBlocker = String.format(java.util.Locale.ROOT, "box y %.2f..%.2f top=%.2f at %.2f,%.2f", b[0], b[1], top, px, pz);
            return new double[]{top, 1};
        }
        double ground = Double.NEGATIVE_INFINITY;
        for (double[] b : boxes) if (b[1] <= top + eps) ground = Math.max(ground, b[1]);
        if (ground < top - 3) return new double[]{top, 2};
        return new double[]{ground, 0};
    }

    /** ASCII top view of the built path: arrows are stairs (facing up the slope), o/e flat centre/edge cells, other columns the terrain height mod 36. */
    private static void map(ServerLevel level, java.util.List<HousePaths.Cell> cells) {
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, base = Integer.MAX_VALUE;
        java.util.Map<Long, HousePaths.Cell> at = new java.util.HashMap<>();
        for (HousePaths.Cell c : cells) {
            x0 = Math.min(x0, c.x());
            x1 = Math.max(x1, c.x());
            z0 = Math.min(z0, c.z());
            z1 = Math.max(z1, c.z());
            base = Math.min(base, c.y());
            at.put(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()), c);
        }
        base -= 4;
        CountryRoads.LOG.info("CR-MAP x " + (x0 - 3) + ".." + (x1 + 3) + " z " + (z0 - 3) + ".." + (z1 + 3) + " base " + base + " (digits = height - base, base36)");
        for (int z = z0 - 3; z <= z1 + 3; z++) {
            StringBuilder sb = new StringBuilder();
            for (int x = x0 - 3; x <= x1 + 3; x++) {
                var c = at.get(net.minecraft.world.level.ChunkPos.pack(x, z));
                if (c != null) sb.append(c.stair() >= 0 ? "^>v<".charAt(c.stair()) : c.edge() ? 'e' : 'o');
                else {
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 - base;
                    sb.append(h < 0 ? '-' : Character.forDigit(h % 36, 36));
                }
            }
            CountryRoads.LOG.info("CR-MAP " + sb);
        }
    }

    /** Plan views of the blocks around a cell, layer by layer from one below to five above, to see what stands on and beside the path. */
    private static void layers(ServerLevel level, HousePaths.Cell mid) {
        for (int y = mid.y() + 5; y >= mid.y() - 1; y--) {
            CountryRoads.LOG.info("CR-LAYER y=" + y + " (path y of mid cell " + mid.y() + ") x " + (mid.x() - 8) + ".." + (mid.x() + 8) + " z " + (mid.z() - 6) + ".." + (mid.z() + 6));
            for (int z = mid.z() - 6; z <= mid.z() + 6; z++) {
                StringBuilder sb = new StringBuilder();
                for (int x = mid.x() - 8; x <= mid.x() + 8; x++) {
                    var st = level.getBlockState(new BlockPos(x, y, z));
                    char ch = st.isAir() ? '.' : st.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                        ? "v<^>".charAt(st.getValue(net.minecraft.world.level.block.StairBlock.FACING).get2DDataValue())
                        : st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) ? 'G' : st.is(net.minecraft.world.level.block.Blocks.DIRT) ? 'D'
                        : st.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || st.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) || st.is(net.minecraft.world.level.block.Blocks.PACKED_MUD) || st.is(net.minecraft.world.level.block.Blocks.GRAVEL) ? 'p'
                        : st.is(net.minecraft.tags.BlockTags.LEAVES) ? 'L' : st.is(net.minecraft.tags.BlockTags.LOGS) ? 'T' : st.getCollisionShape(level, new BlockPos(x, y, z)).isEmpty() ? ',' : '#';
                    sb.append(ch);
                }
                CountryRoads.LOG.info("CR-LAYER " + sb);
            }
        }
    }

    private static void shot(double cx, double cy, double cz, double tx, double ty, double tz, String label) {
        double lx = tx - cx, ly = ty - cy, lz = tz - cz;
        CountryRoads.LOG.info(String.format(java.util.Locale.ROOT, "CR-SHOT %.1f %.1f %.1f %.1f %.1f %s", cx, cy, cz,
            Math.toDegrees(Math.atan2(-lx, lz)), -Math.toDegrees(Math.atan2(ly, Math.hypot(lx, lz))), label));
    }

    /**
     * Walks the built path: centre cells must touch along a side, differ by at most one block, every rise must be a
     * stair facing up the slope in its upper cell, and two blocks of headroom must be free. Prints CR-STAKE-WALK.
     */
    private static void walk(ServerLevel level, java.util.List<HousePaths.Cell> cells, BlockPos stake, java.util.Map<Long, String> skipped) {
        int bad = 0, stairs = 0, steps = 0, flat = 0, slabs = 0, mudSteps = 0, stoneSteps = 0;
        int[] halfSteps = {0}, stairSteps = {0};
        HousePaths.Cell prev = null;
        int banks = 0, cutFill = 0, rails = 0, archCells = 0, lanterns = 0, kitCells = 0;
        for (HousePaths.Cell c : cells) {
            BlockPos at = new BlockPos(c.x(), c.y(), c.z());
            var state = level.getBlockState(at);
            if (c.rail()) {
                // a bridge rail: a fence on a beam, or a post, at rail height
                rails++;
                BlockPos fenceAt = at.atY(c.y() + (c.arch() + 2) / 2);
                var top = level.getBlockState(fenceAt);
                if (!top.is(net.minecraft.tags.BlockTags.FENCES) && !top.is(net.minecraft.tags.BlockTags.LOGS)) bad = fail(++bad, "rail missing", fenceAt, top);
                if (c.tidx() == 2) {
                    BlockPos lp = fenceAt.above(level.getBlockState(fenceAt.above()).is(net.minecraft.tags.BlockTags.LOGS) ? 2 : 1);
                    if (level.getBlockState(lp).is(net.minecraft.world.level.block.Blocks.LANTERN)) lanterns++;
                    else bad = fail(++bad, "end post lantern", lp, level.getBlockState(lp));
                }
                continue;
            }
            if (c.bank()) {
                // a bank is ground, not path: it only has to be at its height with nothing standing loose on it
                banks++;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x(), c.z()) - 1;
                if (top != c.y() && !level.getBlockState(new BlockPos(c.x(), top, c.z())).is(net.minecraft.tags.BlockTags.LOGS)) bad = fail(++bad, "bank height " + top, at, state);
                continue;
            }
            if (c.kit()) {
                // a kit bridge: its blocks are the user's design, the walk is proven by CR-STAKE-SIM
                kitCells++;
                if (!c.edge()) {
                    if (prev != null && Math.abs(c.x() - prev.x()) + Math.abs(c.z() - prev.z()) != 1) bad = fail(++bad, "cells do not touch", at, state);
                    prev = c;
                }
                continue;
            }
            if (c.water() && c.arch() > 0) {
                // an arched deck cell: the plank arch/2 up (a slab on it for an odd half), room above the top
                archCells++;
                BlockPos deck = at.above(c.arch() / 2);
                if (!level.getBlockState(deck).is(net.minecraft.tags.BlockTags.PLANKS)) bad = fail(++bad, "arch deck", deck, level.getBlockState(deck));
                BlockPos top = (c.arch() & 1) == 1 ? deck.above() : deck;
                if ((c.arch() & 1) == 1 && !level.getBlockState(top).is(net.minecraft.tags.BlockTags.WOODEN_SLABS)) bad = fail(++bad, "arch slab", top, level.getBlockState(top));
                for (int dy = 1; dy <= 2; dy++)
                    if (!level.getBlockState(top.above(dy)).getCollisionShape(level, top.above(dy)).isEmpty()) bad = fail(++bad, "arch headroom", top.above(dy), level.getBlockState(top.above(dy)));
                if (!c.edge()) {
                    if (prev != null && Math.abs(c.x() - prev.x()) + Math.abs(c.z() - prev.z()) != 1) bad = fail(++bad, "cells do not touch", at, state);
                    prev = c;
                }
                continue;
            }
            boolean isStair = state.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            if (isStair) stairs++;
            if (isStair != (c.stair() >= 0)) bad = fail(++bad, "stair mismatch", at, state);
            if (isStair && state.getValue(net.minecraft.world.level.block.StairBlock.FACING) != HousePaths.DIRS[c.stair()]) bad = fail(++bad, "stair facing", at, state);
            boolean isSlab = state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
            if (isSlab) slabs++;
            if ((isSlab || isStair) && !c.water()) {
                if (state.is(net.minecraft.world.level.block.Blocks.MUD_BRICK_SLAB) || state.is(net.minecraft.world.level.block.Blocks.MUD_BRICK_STAIRS)) mudSteps++;
                else stoneSteps++;
            }
            if (isSlab != c.slab()) bad = fail(++bad, "slab mismatch", at, state);
            if (!isStair && !isSlab && !state.isSolid() && !c.water()) bad = fail(++bad, "surface not solid", at, state);
            for (int dy = 1; dy <= 2; dy++)
                if (!level.getBlockState(at.above(dy)).getCollisionShape(level, at.above(dy)).isEmpty() && !level.getBlockState(at.above(dy)).is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)) bad = fail(++bad, "headroom", at.above(dy), level.getBlockState(at.above(dy)));
            if (!c.edge()) {
                if (prev != null) {
                    int dy = c.y() - prev.y(), man = Math.abs(c.x() - prev.x()) + Math.abs(c.z() - prev.z());
                    if (man != 1) bad = fail(++bad, "cells do not touch", at, state);
                    if (Math.abs(dy) > 1) bad = fail(++bad, "step over one block", at, state);
                    if (dy == 0) flat++;
                    else steps++;
                    var upper = dy > 0 ? c : prev;
                    if (Math.abs(dy) == 1 && upper.stair() < 0 && !upper.slab()) bad = fail(++bad, "step without stair or slab", new BlockPos(upper.x(), upper.y(), upper.z()), state);
                    if (Math.abs(dy) == 1 && !c.edge()) (upper.slab() ? halfSteps : stairSteps)[0]++;
                }
                prev = c;
            }
        }
        java.util.Set<Long> have = new java.util.HashSet<>();
        for (HousePaths.Cell c : cells) have.add(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()));
        int deadEnds = 0;
        for (HousePaths.Cell c : cells) {
            if (c.stair() < 0) continue;
            var d = HousePaths.DIRS[c.stair()];
            if (!have.contains(net.minecraft.world.level.ChunkPos.pack(c.x() + d.getStepX(), c.z() + d.getStepZ())) && !c.edge()
                && !io.github.profetgit.countryroads.world.RoadColumns.isRoad(level, c.x() + d.getStepX(), c.z() + d.getStepZ())) {
                if (++deadEnds <= 6) CountryRoads.LOG.info("CR-STAKE-DEADEND stair at " + c.x() + "," + c.y() + "," + c.z() + " faces " + d + " into terrain");
            }
        }
        CountryRoads.LOG.info("CR-STAKE-DEADENDS " + deadEnds);
        {
            // bridges must be straight: no bend on a deck cell or right before one
            int bends = 0, run = 0, longest = 0;
            HousePaths.Cell pc = null;
            for (HousePaths.Cell c : cells) {
                if (c.edge() || c.bank()) continue;
                run = c.water() || c.kit() ? run + 1 : 0;
                longest = Math.max(longest, run);
                if (pc != null && pc.dir() != c.dir() && (pc.water() || c.water() || pc.kit() || c.kit())) {
                    if (++bends <= 4) CountryRoads.LOG.info("CR-STAKE-BRIDGE-BEND at " + c.x() + "," + c.y() + "," + c.z());
                }
                pc = c;
            }
            // posts come in pairs, opposite each other across the deck
            java.util.Map<Long, HousePaths.Cell> railAt = new java.util.HashMap<>();
            for (HousePaths.Cell c : cells) if (c.rail()) railAt.put(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()), c);
            int unpaired = 0;
            for (HousePaths.Cell c : cells) {
                if (!c.rail() || c.tidx() == 0) continue;
                int rx = -HousePaths.DIRS[c.dir()].getStepZ(), rz = HousePaths.DIRS[c.dir()].getStepX();
                HousePaths.Cell m1 = railAt.get(net.minecraft.world.level.ChunkPos.pack(c.x() + 4 * rx, c.z() + 4 * rz)), m2 = railAt.get(net.minecraft.world.level.ChunkPos.pack(c.x() - 4 * rx, c.z() - 4 * rz));
                if (!(m1 != null && m1.tidx() > 0 || m2 != null && m2.tidx() > 0)) {
                    if (++unpaired <= 4) CountryRoads.LOG.info("CR-STAKE-BRIDGE-UNPAIRED post at " + c.x() + "," + c.y() + "," + c.z());
                }
            }
            CountryRoads.LOG.info("CR-STAKE-BRIDGE " + (bends == 0 && unpaired == 0 ? "PASS" : "FAIL") + " bends=" + bends + " longest=" + longest + " unpairedPosts=" + unpaired);
        }
        {
            int runs = 0, longest = 0, len = 0, pdx = 9, pdz = 9, over10 = 0;
            HousePaths.Cell pc = null;
            for (HousePaths.Cell c : cells) {
                if (c.edge()) continue;
                if (pc != null) {
                    int dx = c.x() - pc.x(), dz = c.z() - pc.z();
                    if (dx == pdx && dz == pdz) len++;
                    else {
                        if (len > 0) { runs++; longest = Math.max(longest, len); if (len > 10) over10++; }
                        len = 1;
                        pdx = dx;
                        pdz = dz;
                    }
                }
                pc = c;
            }
            runs++;
            longest = Math.max(longest, len);
            CountryRoads.LOG.info("CR-STAKE-SHAPE runs=" + runs + " longest=" + longest + " runsOver10=" + over10);
        }
        int above = 0;
        for (HousePaths.Cell c : cells)
            for (int dy = 1; dy <= 8 && c.tun() < 1 && !c.bank() && !c.rail() && c.arch() == 0 && !c.kit(); dy++) {
                BlockPos q = new BlockPos(c.x(), c.y() + dy, c.z());
                var st = level.getBlockState(q);
                // canopy from four blocks up is allowed (trees are never cut back further); anything else is left over
                boolean leaves = st.is(net.minecraft.tags.BlockTags.LEAVES);
                if (leaves ? dy <= HousePaths.LEAF_ROOM : !st.isAir() && !st.getCollisionShape(level, q).isEmpty()) {
                    if (++above <= 10) CountryRoads.LOG.info("CR-STAKE-ABOVE " + st.getBlock().getDescriptionId() + " " + dy + " above cell " + c.x() + "," + c.y() + "," + c.z() + (c.edge() ? " edge" : "") + (c.stair() >= 0 ? " stair" : "") + " tun=" + c.tun() + (c.fringe() ? " fringe" : ""));
                }
            }
        CountryRoads.LOG.info("CR-STAKE-ABOVE-TOTAL " + above);
        int tCells = 0, tPortals = 0, tBad = 0, tFrames = 0;
        for (HousePaths.Cell c : cells) {
            if (c.tun() < 1) continue;
            tCells++;
            if (c.tun() == 2 && !c.edge()) tPortals++;
            if (HousePaths.isFrame(c)) tFrames++;
            BlockPos at = new BlockPos(c.x(), c.y(), c.z());
            for (int dy = 1; dy <= 3; dy++)
                if (!level.getBlockState(at.above(dy)).getCollisionShape(level, at.above(dy)).isEmpty() && !level.getBlockState(at.above(dy)).is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)) {
                    if (++tBad <= 5) CountryRoads.LOG.info("CR-STAKE-TUNNEL-BAD blocked interior " + dy + " above " + at.toShortString() + " " + level.getBlockState(at.above(dy)));
                }
            if (!level.getBlockState(at.above(4)).isSolid()) {
                if (++tBad <= 5) CountryRoads.LOG.info("CR-STAKE-TUNNEL-BAD no roof above " + at.toShortString());
            }
        }
        if (tCells > 0) {
            int torches = 0;
            { PathData sbe = HousePaths.data(level, stake); if (sbe != null) torches = sbe.torchPos.size(); }
            CountryRoads.LOG.info("CR-STAKE-TUNNEL " + (tBad == 0 ? "PASS" : "FAIL") + " cells=" + tCells + " portals=" + tPortals + " frames=" + tFrames + " torches=" + torches + " bad=" + tBad);
        }
        int walled = 0, centreN = 0, stairWalled = 0, stairN = 0;
        for (HousePaths.Cell c : cells) {
            if (c.edge()) continue;
            centreN++;
            boolean w = false;
            for (var d : HousePaths.DIRS) {
                if (have.contains(net.minecraft.world.level.ChunkPos.pack(c.x() + d.getStepX(), c.z() + d.getStepZ()))) continue;
                BlockPos q = new BlockPos(c.x() + d.getStepX(), c.y() + 1, c.z() + d.getStepZ());
                if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() && !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) w = true;
            }
            if (w) walled++;
            if (c.stair() >= 0) {
                stairN++;
                if (w) stairWalled++;
            }
        }
        CountryRoads.LOG.info("CR-STAKE-WALLS centre cells with a 2-high wall beside: " + walled + "/" + centreN + ", stair cells: " + stairWalled + "/" + stairN);
        // width: centre cells whose two side columns (across the direction of travel) are path at the same height
        java.util.Map<Long, HousePaths.Cell> at = new java.util.HashMap<>();
        for (HousePaths.Cell c : cells) if (!c.bank() && !c.rail()) at.put(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()), c);
        // a side counts when it is path (not a fringe straggler) within half a block of the cell: a slab beside a full block still walks
        // at a bend the cell's side is the path itself going on (or coming in): that side is path, not narrowing
        java.util.Map<Long, Integer> lineAt = new java.util.HashMap<>();
        java.util.List<HousePaths.Cell> lineCells = new java.util.ArrayList<>();
        for (HousePaths.Cell c : cells)
            if (!c.edge() && !c.bank()) {
                lineAt.put(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()), lineCells.size());
                lineCells.add(c);
            }
        int wide = 0, open = 0, two = 0, one = 0;
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        for (HousePaths.Cell c : cells) {
            if (c.edge() || c.tun() >= 0) continue;
            open++;
            int rx = -HousePaths.DIRS[c.dir()].getStepZ(), rz = HousePaths.DIRS[c.dir()].getStepX();
            int sides = 0;
            for (int s = -1; s <= 1; s += 2) {
                long k = net.minecraft.world.level.ChunkPos.pack(c.x() + s * rx, c.z() + s * rz);
                HousePaths.Cell a = at.get(k);
                Integer li = lineAt.get(k), ci = lineAt.get(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()));
                if (a != null && !a.fringe() && (Math.abs(stand(a) - stand(c)) <= 0.5 || li != null && ci != null && Math.abs(li - ci) == 1)) {
                    sides++;
                    continue;
                }
                String r = a != null ? (a.fringe() ? "fringe" : "height" + (a.y() - c.y())) : skipped != null && skipped.containsKey(k) ? skipped.get(k) : "none";
                if (r.startsWith("bedMax")) r = "bedMax";
                why.merge(r, 1, Integer::sum);
                if (why.get(r) <= 4) {
                    int g = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x() + s * rx, c.z() + s * rz) - 1;
                    CountryRoads.LOG.info("CR-STAKE-NARROW " + r + " beside " + c.x() + "," + c.y() + "," + c.z() + " #" + ci + (li != null ? " side #" + li : a != null ? " side bed" : "") + (c.stair() >= 0 ? " stair" : "") + (c.slab() ? " slab" : "") + " side top now y" + g + " " + level.getBlockState(new BlockPos(c.x() + s * rx, g, c.z() + s * rz)).getBlock().getDescriptionId().replace("block.minecraft.", ""));
                }
            }
            if (sides == 2) wide++;
            else if (sides == 1) two++;
            else if (++one <= 8) CountryRoads.LOG.info("CR-STAKE-ONEWIDE at " + c.x() + "," + c.y() + "," + c.z());
        }
        int pct = open == 0 ? 100 : wide * 100 / open;
        CountryRoads.LOG.info("CR-STAKE-WIDTH " + (pct >= 95 && one == 0 ? "PASS" : "FAIL") + " three wide " + wide + "/" + open + " (" + pct + "%) two=" + two + " one=" + one + " banks=" + banks + " why=" + why);
        CountryRoads.LOG.info("CR-STAKE-WALK " + (bad == 0 ? "PASS" : "FAIL") + " cells=" + cells.size() + " flat=" + flat + " steps=" + steps + " (half " + halfSteps[0] + ", stair " + stairSteps[0] + ") stairs=" + stairs + " rails=" + rails + " kit=" + kitCells + " lanterns=" + lanterns + " arched=" + archCells + " slabs=" + slabs + " (mud " + mudSteps + ", other " + stoneSteps + ") bad=" + bad
            + " palette=" + io.github.profetgit.countryroads.paint.Palette.surface(level.getBiome(stake)).name() + " at=" + stake.toShortString());
    }

    /** Within 8 columns of a kit bridge's footprint (where its tree clearing reaches). */
    private static boolean nearBridge(java.util.List<io.github.profetgit.countryroads.path.BridgeKit.Bridge> bridges, BlockPos p) {
        if (bridges == null) return false;
        for (var br : bridges) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int z : new int[]{0, br.length() - 1})
                for (int x : new int[]{0, io.github.profetgit.countryroads.path.BridgeKit.WIDTH - 1}) {
                    var c = io.github.profetgit.countryroads.path.BridgeKit.worldPos(br, x, 0, z);
                    x0 = Math.min(x0, c.getX()); x1 = Math.max(x1, c.getX()); z0 = Math.min(z0, c.getZ()); z1 = Math.max(z1, c.getZ());
                }
            if (p.getX() >= x0 - 8 && p.getX() <= x1 + 8 && p.getZ() >= z0 - 8 && p.getZ() <= z1 + 8) return true;
        }
        return false;
    }

    /** Feet height on a path cell: a bottom slab is half a block lower than a full block or a stair's top. */
    private static double stand(HousePaths.Cell c) {
        return c.y() + (c.slab() ? 0.5 : 1) + c.arch() / 2.0;
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
