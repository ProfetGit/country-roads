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

    /** Blocks force-loaded around the stake before the survey: the default, warm (the whole survey window) and cold (only the stake's chunks). */
    static final int PRELOAD = 170, WARM = 330, COLD = 0;

    static int run(CommandSourceStack src, int x, int z, boolean keep) {
        return run(src, x, z, keep, Integer.MIN_VALUE, 0, PRELOAD);
    }

    static int run(CommandSourceStack src, int x, int z, boolean keep, int viaX, int viaZ) {
        return run(src, x, z, keep, viaX, viaZ, PRELOAD);
    }

    static int run(CommandSourceStack src, int x, int z, boolean keep, int viaX, int viaZ, int preload) {
        ServerLevel level = src.getLevel();
        int r = preload, cr = (r >> 4) + 1;
        int[] phase = {-2}, settle = {0, 0};
        int[] built = new int[2];
        BlockPos[] stakeRef = new BlockPos[1], viaRef = new BlockPos[1];
        int[][] before = new int[1][];
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>[] beforeStates = new java.util.Map[1];
        @SuppressWarnings("unchecked")
        java.util.List<HousePaths.Cell>[] cells = new java.util.List[1];
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
                level.setBlock(stakeRef[0], Stake.BLOCK.defaultBlockState(), 3);
                HousePaths.survey(level, stakeRef[0], null);
                if (viaX != Integer.MIN_VALUE) {
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) hold(level, (viaX >> 4) + dx, (viaZ >> 4) + dz, true);
                    int vy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, viaX, viaZ);
                    BlockPos vp = new BlockPos(viaX, vy, viaZ);
                    level.setBlock(vp, Stake.BLOCK.defaultBlockState(), 3);
                    viaRef[0] = vp;
                    HousePaths.placed(level, vp, null);
                }
                phase[0] = 0;
                return false;
            }
            BlockPos stake = stakeRef[0];
            if (HousePaths.busy()) return false;
            StakeBlockEntity be = (StakeBlockEntity) level.getBlockEntity(stake);
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
                    map(level, cells[0]);
                    sim(level, cells[0]);
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
                        var ahead = cs.get(Math.min(cs.size() - 1, cs.indexOf(far) + 9));
                        shot(ahead.x() + 0.5, ahead.y() + 5, ahead.z() + 0.5, far.x() + 0.5, far.y() + 1, far.z() + 0.5, "hpback");
                        CountryRoads.LOG.info("CR-STAKE KEPT cells=" + built[0] + " journal=" + built[1]);
                        return true;
                    }
                    int logsCut = 0, leavesCut = 0;
                    for (var e : be.journal) {
                        if (e.state().is(net.minecraft.tags.BlockTags.LOGS)) logsCut++;
                        else if (e.state().is(net.minecraft.tags.BlockTags.LEAVES)) leavesCut++;
                    }
                    CountryRoads.LOG.info("CR-STAKE-TREES logs removed " + logsCut + " (must be 0), leaves removed " + leavesCut);
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
                        CountryRoads.LOG.info("CR-STAKE-WAYPOINT " + (onRoute ? "PASS" : "FAIL") + " onRoute=" + onRoute + " stakeRemoved=" + !level.getBlockState(viaRef[0]).is(Stake.BLOCK));
                    }
                    int[] mid = snapshot(level, cells[0]);
                    int changed = diff(before[0], mid);
                    HousePaths.takeUp(level, stake);
                    // a waypoint stake is used up by the build (it comes back as an item): put it back for the comparison
                    if (viaRef[0] != null) level.setBlock(viaRef[0], Stake.BLOCK.defaultBlockState(), 3);
                    int[] after = snapshot(level, cells[0]);
                    int left = diff(before[0], after);
                    if (left > 0) explain(level, cells[0], beforeStates[0]);
                    level.setBlock(stake, Blocks.AIR.defaultBlockState(), 3);
                    if (viaRef[0] != null) level.setBlock(viaRef[0], Blocks.AIR.defaultBlockState(), 3);
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
                    if (level.getBlockEntity(s) instanceof StakeBlockEntity be && be.status == StakeBlockEntity.PREVIEW) previews++;
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
    private static void walk(ServerLevel level, java.util.List<HousePaths.Cell> cells, BlockPos stake) {
        int bad = 0, stairs = 0, steps = 0, flat = 0;
        HousePaths.Cell prev = null;
        int banks = 0, cutFill = 0;
        for (HousePaths.Cell c : cells) {
            BlockPos at = new BlockPos(c.x(), c.y(), c.z());
            var state = level.getBlockState(at);
            if (c.bank()) {
                // a bank is ground, not path: it only has to be at its height with nothing standing loose on it
                banks++;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x(), c.z()) - 1;
                if (top != c.y() && !level.getBlockState(new BlockPos(c.x(), top, c.z())).is(net.minecraft.tags.BlockTags.LOGS)) bad = fail(++bad, "bank height " + top, at, state);
                continue;
            }
            boolean isStair = state.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            if (isStair) stairs++;
            if (isStair != (c.stair() >= 0)) bad = fail(++bad, "stair mismatch", at, state);
            if (isStair && state.getValue(net.minecraft.world.level.block.StairBlock.FACING) != HousePaths.DIRS[c.stair()]) bad = fail(++bad, "stair facing", at, state);
            if (!isStair && !state.isSolid() && !c.water()) bad = fail(++bad, "surface not solid", at, state);
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
                    if (Math.abs(dy) == 1 && upper.stair() < 0) bad = fail(++bad, "step without stair", new BlockPos(upper.x(), upper.y(), upper.z()), state);
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
            for (int dy = 1; dy <= 8 && c.tun() < 1 && !c.bank(); dy++) {
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
            if (level.getBlockEntity(stake) instanceof StakeBlockEntity sbe) torches = sbe.torchPos.size();
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
        for (HousePaths.Cell c : cells) if (!c.bank()) at.put(net.minecraft.world.level.ChunkPos.pack(c.x(), c.z()), c);
        int wide = 0, open = 0;
        for (HousePaths.Cell c : cells) {
            if (c.edge() || c.tun() >= 0) continue;
            open++;
            int rx = -HousePaths.DIRS[c.dir()].getStepZ(), rz = HousePaths.DIRS[c.dir()].getStepX();
            HousePaths.Cell a = at.get(net.minecraft.world.level.ChunkPos.pack(c.x() + rx, c.z() + rz)), b = at.get(net.minecraft.world.level.ChunkPos.pack(c.x() - rx, c.z() - rz));
            if (a != null && b != null && a.y() == c.y() && b.y() == c.y()) wide++;
        }
        CountryRoads.LOG.info("CR-STAKE-WIDTH three wide " + wide + "/" + open + " (" + (open == 0 ? 0 : wide * 100 / open) + "%) banks=" + banks);
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
