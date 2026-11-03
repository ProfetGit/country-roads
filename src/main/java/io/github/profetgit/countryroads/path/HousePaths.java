package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.paint.Palette;
import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.RoadColumns;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * House paths. Placing a Surveyor's Stake runs a survey (time-sliced search over the real terrain to the nearest road
 * column) and shows the route as a line of particles to the owner. Right-clicking the stake builds it, free (since
 * 0.1.19: the blocks with item value drop nothing, see PathBlocks), and the path rolls out from the door to the road.
 * Breaking the stake restores every block it changed (and refunds what an older build was paid).
 */
public final class HousePaths {
    static final int PREVIEW_TICKS = 2400, CONTAINER_REACH = 8, WAYPOINT_RADIUS = 128, PREVIEW_REACH = 96;
    static final long SLICE_NANOS = 1_500_000, SLICE_IDLE_NANOS = 4_000_000;
    /**
     * Path blocks go in (and come back out) exactly, without neighbour updates: a full update would break the top half
     * of tall grass before it is journaled, and turn path under a slab into dirt.
     */
    static final int EXACT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final Set<Item> EARTH = Set.of(Items.DIRT, Items.COARSE_DIRT, Items.GRAVEL, Items.SAND, Items.RED_SAND, Items.PACKED_MUD,
        Items.MUD, Items.ROOTED_DIRT, Items.COBBLESTONE, Items.DIRT_PATH);
    /** What the stone steps are paid with (they can be mined, so they are never free). */
    static final Set<Item> STONE = Set.of(Items.COBBLESTONE, Items.MOSSY_COBBLESTONE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE,
        Items.COBBLED_DEEPSLATE, Items.TUFF, Items.SANDSTONE, Items.RED_SANDSTONE, Items.MUD_BRICKS);

    /**
     * One path cell: column, surface block Y, deck over water, a column beside the walked line (bed, fringe or bank), the
     * stair facing (index into DIRS, -1 for a flat block), the direction of travel into it, whether it is a stray path
     * block in the verge, the tunnel part (-1 none, 0 open cut, 1 inside the rock, 2 portal) with the index along the
     * roofed run, and whether it is a bank: no path there, only the ground cut or filled to height y.
     */
    public record Cell(int x, int z, int y, boolean water, boolean edge, int stair, int dir, boolean fringe, int tun, int tidx, boolean bank, boolean slab, boolean rail, int arch, boolean kit) {}

    /** A finished survey: cells from the door to the road, the bill, and what the route is made of. */
    public record Survey(List<Cell> cells, int earth, int stone, int planks, int torches, int lanterns, int tunnelCells, int length, Map<Long, String> skipped, List<BridgeKit.Bridge> bridges,
                         List<int[]> misses) {}

    public static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    private record Rollout(ServerLevel level, BlockPos stake, List<Cell> cells, int[] next, java.util.Set<Long> chunks, int[] age, java.util.Set<Long> cols, java.util.Set<Long> rocky,
                           List<BridgeKit.Bridge> bridges, java.util.Set<Integer> stamped) {}

    private record Preview(ServerLevel level, BlockPos stake, ServerPlayer player, List<Cell> cells, long until, int[] legs) {}

    private static final List<PathSearch> SURVEYS = new ArrayList<>();
    private static final List<Rollout> ROLLOUTS = new ArrayList<>();
    private static final Map<BlockPos, Preview> PREVIEWS = new HashMap<>();

    private HousePaths() {}

    /** To the player's HUD card, or to the log when a test drives the stake without one. */
    private static void note(ServerPlayer player, int code) {
        PathNotice.send(player, code, 0, 0, 0, false);
    }

    private static void note(ServerPlayer player, int code, int a) {
        PathNotice.send(player, code, a, 0, 0, false);
    }

    /** Mod init: the survey's chunk ticket type is registered with the other types. */
    /** The survey's chunk ticket type. */
    public static void register(io.github.profetgit.countryroads.CountryRoads.Registrar r) {
        PathSearch.register(r);
    }

    /** Survey chunk tickets held right now (all surveys). */
    public static int surveyTickets() {
        return PathSearch.held;
    }

    public static boolean busy() {
        return !SURVEYS.isEmpty() || !ROLLOUTS.isEmpty() || !DEBOUNCE.isEmpty();
    }

    static long key(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    /** The stake that placed a path's survey, for messages when a waypoint is added or taken away. */
    private static final Map<BlockPos, ServerPlayer> OWNERS = new HashMap<>();
    /** Paths being planned (surveyed, not built): a stake placed near one is its waypoint, whenever the last survey finished. */
    private record Open(ServerLevel level, ServerPlayer player) {}
    private static final Map<BlockPos, Open> OPEN = new HashMap<>();
    /** A path whose waypoints changed is surveyed again once the player stops placing stakes (about a second). */
    private record Due(ServerLevel level, ServerPlayer player, long tick) {}
    private static final Map<BlockPos, Due> DEBOUNCE = new HashMap<>();
    static final int DEBOUNCE_TICKS = 20;
    /** Dust colours of the legs of a path with waypoints (door to the first stake, then on). */
    static final int[] LEG_COLORS = {0xF5D76E, 0x4FC3F7, 0x81C784, 0xBA68C8, 0xFFB74D, 0xE57373};

    /** The leg of every cell: how many waypoints the route has passed (the cell on a waypoint ends its leg). */
    public static int[] legsOf(List<Cell> cells, List<BlockPos> waypoints) {
        int[] legs = new int[cells.size()];
        int k = 0;
        for (int i = 0; i < legs.length; i++) {
            legs[i] = k;
            Cell c = cells.get(i);
            if (!c.edge && k < waypoints.size() && c.x == waypoints.get(k).getX() && c.z == waypoints.get(k).getZ()) k++;
        }
        return legs;
    }

    private static void later(ServerLevel level, BlockPos door, ServerPlayer player) {
        DEBOUNCE.put(door, new Due(level, player, level.getGameTime() + DEBOUNCE_TICKS));
    }

    /**
     * What a path remembers: the stake's own block entity, or the record of a path started from a door or fence gate
     * (keyed by the door's position). Null when there is none.
     */
    public static PathData data(ServerLevel level, BlockPos anchor) {
        if (level.getBlockEntity(anchor) instanceof StakeBlockEntity s) return s.data;
        return PathRecords.of(level).door(anchor);
    }

    /** A stake block, or the door or fence gate a path was started from. */
    static boolean anchored(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.is(Stake.BLOCK) || isDoorLike(st);
    }

    public static boolean isDoorLike(BlockState st) {
        return st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    /** The key of a door: its lower half. */
    public static BlockPos anchorOf(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
            return pos.below();
        return pos;
    }

    /** Open ground: the top block of the column is not built, a log, a stake or fluid, and not air (the void). */
    private static boolean open(ServerLevel level, int x, int z, int floorY) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (Math.abs(top - floorY) > 4) return false;
        BlockState s = level.getBlockState(new BlockPos(x, top, z));
        return !s.isAir() && !Retrofit.isCrafted(s) && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && s.getFluidState().isEmpty() && !s.is(Stake.BLOCK);
    }

    /**
     * Where a path from a door starts: the nearest open ground within 8 blocks that is not walled in (a roof over the
     * porch, doorstep stairs, fence posts and a closed yard all close columns, like the search itself sees them).
     * Prefers the side the player is on. Null when every open spot nearby is boxed in.
     */
    static BlockPos findStart(ServerLevel level, BlockPos anchor, Direction outside) {
        int floorY = anchor.getY() - 1;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                if ((dx == 0 && dz == 0) || !open(level, x, z, floorY)) continue;
                double score = dx * dx + dz * dz + (dx * outside.getStepX() + dz * outside.getStepZ() <= 0 ? 60 : 0);
                if (score >= bestScore) continue;
                if (!escapes(level, x, z, anchor, floorY)) continue;
                bestScore = score;
                best = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            }
        return best;
    }

    /** Can one walk from this open column over open columns (steps of at most one block) to 12 blocks from the door? Budget 600 columns. */
    private static boolean escapes(ServerLevel level, int x0, int z0, BlockPos anchor, int floorY) {
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(new int[]{x0, z0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0)});
        seen.add(key(x0, z0));
        int budget = 600;
        while (!queue.isEmpty() && budget-- > 0) {
            int[] c = queue.poll();
            if (Math.max(Math.abs(c[0] - anchor.getX()), Math.abs(c[1] - anchor.getZ())) >= 12) return true;
            for (int d = 0; d < 4; d++) {
                int nx = c[0] + DX[d], nz = c[1] + DZ[d];
                if (!seen.add(key(nx, nz)) || !open(level, nx, nz, floorY)) continue;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (Math.abs(h - c[2]) > 1) continue;
                queue.add(new int[]{nx, nz, h});
            }
        }
        return false;
    }

    /** The stake item was used on a door or fence gate: survey, build or ask what to do with the built path. `outside`: the side the player is on. */
    public static void useDoor(ServerLevel level, BlockPos door, ServerPlayer player, Direction outside) {
        BlockPos anchor = anchorOf(level, door);
        PathData d = PathRecords.of(level).open(anchor);
        if (d.status == PathData.BUILT && player != null && player.isShiftKeyDown()) {
            keep(level, anchor, player);
            return;
        }
        if (d.status != PathData.BUILT && player != null && player.isShiftKeyDown() && !d.waypoints.isEmpty()) {
            toggleEnd(level, anchor, player);
            return;
        }
        if (d.status != PathData.BUILT) {
            d.start = findStart(level, anchor, outside);
            if (d.start == null) {
                PathRecords.of(level).drop(anchor);
                note(player, PathNotice.DOOR_BOXED);
                if (player == null) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STAKE-DOORBOXED no open start within 8 blocks");
                return;
            }
            int far = Math.max(Math.abs(d.start.getX() - anchor.getX()), Math.abs(d.start.getZ() - anchor.getZ()));
            if (far > 3) note(player, PathNotice.START_FAR, far);
        }
        use(level, anchor, player);
    }

    /**
     * A stake was placed. Near a stake of the same player whose path is not built yet it is a waypoint of that path
     * (the route is surveyed again through it); otherwise it is the door of a new path.
     */
    public static void placed(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockPos door = player != null && player.isShiftKeyDown() ? null : nearestDoor(level, pos, player);
        PathData wbe = data(level, pos), dbe = door == null ? null : data(level, door);
        if (door == null || wbe == null || dbe == null) {
            survey(level, pos, player);
            return;
        }
        wbe.role = PathData.WAYPOINT;
        wbe.door = door;
        dbe.waypoints.add(pos.immutable());
        dbe.setChanged();
        wbe.setChanged();
        PathNotice.send(player, PathNotice.WAYPOINT, dbe.waypoints.size(), 0, 0, dbe.ends);
        later(level, door, player);
    }

    /**
     * Sneak + right-click on a stake of a path not built yet: the path ends at its last stake and joins no road, or (again)
     * goes on to the nearest road. Surveyed again at once.
     */
    public static void toggleEnd(ServerLevel level, BlockPos stake, ServerPlayer player) {
        PathData be = data(level, stake);
        if (be == null || be.status == PathData.BUILT) return;
        BlockPos door = be.role == PathData.WAYPOINT && be.door != null ? be.door : stake;
        PathData d = data(level, door);
        if (d == null) return;
        d.waypoints.removeIf(w -> !level.getBlockState(w).is(Stake.BLOCK));
        if (d.waypoints.isEmpty()) {
            note(player, PathNotice.NEEDS_TWO);
            return;
        }
        d.ends = !d.ends;
        d.setChanged();
        note(player, d.ends ? PathNotice.END_ON : PathNotice.END_OFF, d.waypoints.size());
        survey(level, door, player);
    }

    private static BlockPos nearestDoor(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        List<BlockPos> doors = new ArrayList<>();
        OPEN.entrySet().removeIf(e -> !anchored(e.getValue().level, e.getKey()));
        for (var e : OPEN.entrySet()) if (e.getValue().level == level && e.getValue().player == player) doors.add(e.getKey());
        for (BlockPos d : doors) {
            if (d.equals(pos) || Math.max(Math.abs(d.getX() - pos.getX()), Math.abs(d.getZ() - pos.getZ())) > WAYPOINT_RADIUS) continue;
            PathData be = data(level, d);
            if (be == null || be.role != PathData.DOOR || be.status == PathData.BUILT) continue;
            double dist = d.distSqr(pos);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    /** Stops the surveys of a stake (their tickets go, a late result is dropped). */
    private static void cancelSurveys(BlockPos stake) {
        SURVEYS.removeIf(j -> {
            if (!j.stake.equals(stake)) return false;
            j.cancel();
            return true;
        });
    }

    /** Dev: time the column capture over the loaded chunks within r blocks of (x, z), several rounds. */
    public static String captureBench(ServerLevel level, int x, int z, int r) {
        StringBuilder sb = new StringBuilder("CR-CAPTURE");
        for (int round = 0; round < 6; round++) {
            int n = 0;
            long t0 = System.nanoTime();
            for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
                for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                    var c = level.getChunkSource().getChunkNow(cx, cz);
                    if (c == null) continue;
                    ColumnSnapshot.capture(c, level.getMinY());
                    n++;
                }
            sb.append(String.format(java.util.Locale.ROOT, " r%d=%d chunks %.1fus", round, n, n == 0 ? 0 : (System.nanoTime() - t0) / 1000.0 / n));
        }
        return sb.toString();
    }

    /**
     * Dev: compares the snapshot with live reads (the old survey's) for every column within r of (x, z) whose chunks
     * are loaded: ground and tree cost. Prints CR-SNAPCHECK with the mismatch counts and the first few.
     */
    public static String snapCheck(ServerLevel level, int x, int z, int r) {
        ColumnSnapshot snap = new ColumnSnapshot();
        for (int cx = (x - r - 1) >> 4; cx <= (x + r + 1) >> 4; cx++)
            for (int cz = (z - r - 1) >> 4; cz <= (z + r + 1) >> 4; cz++) {
                var c = level.getChunkSource().getChunkNow(cx, cz);
                if (c != null) snap.chunks.put(ChunkPos.pack(cx, cz), ColumnSnapshot.capture(c, level.getMinY()));
            }
        ColumnSnapshot.View view = new ColumnSnapshot.View(snap, x, z, r + 2, List.of());
        int cols = 0, badGround = 0, badTree = 0;
        StringBuilder ex = new StringBuilder();
        for (int qx = x - r; qx <= x + r; qx++)
            for (int qz = z - r; qz <= z + r; qz++) {
                if (snap.get(qx >> 4, qz >> 4) == null) continue;
                cols++;
                int live = liveGround(level, qx, qz), g = view.ground(qx, qz);
                if (live != g) {
                    if (badGround++ < 4) ex.append(" ground ").append(qx).append(',').append(qz).append(" live=").append(live).append(" snap=").append(g);
                    continue;
                }
                if (!Terrain.usable(g)) continue;
                int walk = Terrain.level(g);
                boolean nbLoaded = true;
                for (Direction dd : DIRS) if (snap.get((qx + dd.getStepX()) >> 4, (qz + dd.getStepZ()) >> 4) == null) nbLoaded = false;
                if (!nbLoaded) continue;
                for (int w = walk - 1; w <= walk + 1; w++) {
                    int lt = liveTree(level, qx, qz, w), st = view.treeCost(qx, qz, w);
                    if (lt != st) {
                        if (badTree++ < 4) {
                            ex.append(" tree ").append(qx).append(',').append(qz).append(" walk=").append(w).append(" live=").append(lt).append(" snap=").append(st);
                            for (Direction dd : DIRS) {
                                BlockPos np = new BlockPos(qx + dd.getStepX(), w + 1, qz + dd.getStepZ());
                                if (!level.getBlockState(np).is(BlockTags.LOGS)) continue;
                                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, np.getX(), np.getZ()) - 1;
                                ex.append(" [log at y").append(w + 1).append(", column top y").append(top).append('=')
                                    .append(level.getBlockState(np.atY(top)).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(']');
                            }
                        }
                    }
                }
            }
        return "CR-SNAPCHECK " + (badGround == 0 && badTree == 0 ? "PASS" : "FAIL") + " columns=" + cols + " ground=" + badGround + " tree=" + badTree + ex;
    }

    /** The old survey's ground rule, read live (PathSearch.passable before the snapshot). */
    private static int liveGround(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState s = level.getBlockState(new BlockPos(x, top, z));
        if (s.is(Stake.BLOCK)) {
            top--;
            s = level.getBlockState(new BlockPos(x, top, z));
        }
        if (s.is(Blocks.WATER)) {
            int below = top;
            while (below > level.getMinY() && level.getBlockState(new BlockPos(x, below, z)).is(Blocks.WATER) && top - below < 6) below--;
            return top - below >= 6 ? Terrain.DEEP + top : top + Terrain.WET;
        }
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LOGS) || (Retrofit.isCrafted(s) && !RoadColumns.isRoad(level, x, z))) return Terrain.CLOSED;
        return top;
    }

    /** The old survey's tree cost, read live. */
    private static int liveTree(ServerLevel level, int x, int z, int walkY) {
        int v = 0;
        for (int dy = 1; dy <= LEAF_ROOM; dy++) if (level.getBlockState(new BlockPos(x, walkY + dy, z)).is(BlockTags.LEAVES)) v += 14;
        for (Direction dd : DIRS) if (level.getBlockState(new BlockPos(x + dd.getStepX(), walkY + 1, z + dd.getStepZ())).is(BlockTags.LOGS)) v += 8;
        return v;
    }

    /** Dev: is a bore safe (Tunnels.safe at the column's walking level), and what the heights along it are. */
    public static String boreSafe(ServerLevel level, int x, int z, int d, int len) {
        int y = Terrain.level(liveGround(level, x, z));
        StringBuilder sb = new StringBuilder("CR-BORESAFE " + x + "," + z + " d=" + d + " y=" + y + " len=" + len + " safe=" + Tunnels.safe(level, x, z, d, y, len) + " ground:");
        for (int m = 1; m <= len; m++) sb.append(' ').append(Terrain.level(liveGround(level, x + DX[d] * m, z + DZ[d] * m)) - y);
        return sb.toString();
    }

    /** Data packs reloaded: block tags may have changed. */
    public static void reloaded() {
        ColumnSnapshot.forgetKinds();
    }

    /** The server is stopping: nothing carries over to the next world. */
    public static void stop() {
        for (PathSearch j : SURVEYS) j.cancel();
        SURVEYS.clear();
        ColumnSnapshot.forgetKinds();
        ROLLOUTS.clear();
        PREVIEWS.clear();
        OWNERS.clear();
        OPEN.clear();
        DEBOUNCE.clear();
    }

    public static void survey(ServerLevel level, BlockPos stake, ServerPlayer player) {
        if (!io.github.profetgit.countryroads.Config.get().housePaths) {
            note(player, PathNotice.DISABLED);
            return;
        }
        cancelSurveys(stake);
        PREVIEWS.remove(stake);
        List<BlockPos> waypoints = new ArrayList<>();
        BlockPos start = stake;
        boolean ends = false;
        PathData be = data(level, stake);
        if (be != null) {
            be.waypoints.removeIf(w -> !level.getBlockState(w).is(Stake.BLOCK));
            waypoints.addAll(be.waypoints);
            if (be.waypoints.isEmpty()) be.ends = false;
            ends = be.ends;
            if (be.start != null) start = be.start;
            if (be.status == PathData.PREVIEW) {
                be.status = PathData.NONE;
                be.survey = null;
            }
        }
        if (player != null) OWNERS.put(stake, player);
        OPEN.put(stake, new Open(level, player));
        DEBOUNCE.remove(stake);
        SURVEYS.add(new PathSearch(level, stake, start, player, waypoints, ends));
        note(player, PathNotice.SURVEYING);
    }

    public static void use(ServerLevel level, BlockPos stake, ServerPlayer player) {
        PathData be = data(level, stake);
        if (be == null) return;
        if (be.role == PathData.WAYPOINT && be.door != null && data(level, be.door) != null) {
            use(level, be.door, player);
            return;
        }
        if (be.status == PathData.BUILT) {
            note(player, PathNotice.BUILT);
            return;
        }
        if (be.status != PathData.PREVIEW || be.survey == null) {
            survey(level, stake, player);
            return;
        }
        Survey s = be.survey;
        List<ItemStack> paid = new ArrayList<>();
        be.paid.clear();
        be.paid.addAll(paid);
        be.status = PathData.BUILT;
        be.survey = null;
        OPEN.remove(stake);
        DEBOUNCE.remove(stake);
        be.builtAt = level.getGameTime();
        PathRecords.of(level).mark(stake, be.builtAt);
        be.setChanged();
        PREVIEWS.remove(stake);
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (Cell c : s.cells) {
            chunks.add(ChunkPos.pack(c.x >> 4, c.z >> 4));
            if (!c.bank && !c.rail) cols.add(key(c.x, c.z));
        }
        ROLLOUTS.add(new Rollout(level, stake, s.cells, new int[]{0}, chunks, new int[]{0}, cols, rocky(level, s.cells), s.bridges, new java.util.HashSet<>()));
        note(player, PathNotice.BUILDING, s.length());
    }

    /** Sneak + right-click on a built path: it stays for good. */
    public static void keep(ServerLevel level, BlockPos anchor, ServerPlayer player) {
        if (commit(level, anchor)) note(player, PathNotice.COMMITTED);
    }

    /**
     * Left-click on the door or gate a path started from with the stake in hand: take the path up (or drop the preview).
     * False when this door has no path, so a normal click breaks the door.
     */
    public static boolean undoDoor(ServerLevel level, BlockPos door, ServerPlayer player) {
        BlockPos anchor = anchorOf(level, door);
        if (PathRecords.of(level).door(anchor) == null) return false;
        takeUp(level, anchor);
        PathRecords.of(level).drop(anchor);
        note(player, PathNotice.UNDONE);
        return true;
    }

    /** Built paths can be undone for a day (24000 ticks of game time); then the stake goes and the path is part of the land. */
    static final long COMMIT_TICKS = 24000;

    /** The path stays for good: nothing can undo it any more, the stake (if any) is taken away and comes back as an item. */
    public static boolean commit(ServerLevel level, BlockPos anchor) {
        PathData d = data(level, anchor);
        PathRecords records = PathRecords.of(level);
        if (d == null || d.status != PathData.BUILT) {
            records.unmark(anchor);
            return false;
        }
        ROLLOUTS.removeIf(r -> r.stake.equals(anchor));
        d.forget();
        OWNERS.remove(anchor);
        PREVIEWS.remove(anchor);
        if (level.getBlockState(anchor).is(Stake.BLOCK)) {
            records.unmark(anchor);
            level.destroyBlock(anchor, true);
        } else {
            records.drop(anchor);
        }
        return true;
    }

    /** The stake is being broken (or the path undone from a door): put back every block the path changed and give back the materials. */
    public static void takeUp(ServerLevel level, BlockPos stake) {
        ServerPlayer owner = OWNERS.get(stake);
        PREVIEWS.remove(stake);
        OPEN.remove(stake);
        DEBOUNCE.remove(stake);
        cancelSurveys(stake);
        ROLLOUTS.removeIf(r -> r.stake.equals(stake));
        PathData first = data(level, stake);
        if (first != null) {
            if (first.role == PathData.WAYPOINT) {
                // a waypoint taken away: the door surveys again without it
                BlockPos door = first.door;
                first.role = PathData.DOOR;
                PathData dbe = door == null ? null : data(level, door);
                if (dbe != null && dbe.status != PathData.BUILT) {
                    dbe.waypoints.remove(stake);
                    dbe.setChanged();
                    later(level, door, OWNERS.get(door));
                }
                return;
            }
            for (BlockPos w : new ArrayList<>(first.waypoints)) if (level.getBlockState(w).is(Stake.BLOCK)) level.destroyBlock(w, true);
            first.waypoints.clear();
            OWNERS.remove(stake);
        }
        PathData be = first;
        if (be == null || be.status != PathData.BUILT) {
            if (be != null && !level.getBlockState(stake).is(Stake.BLOCK)) PathRecords.of(level).drop(stake);
            note(owner, PathNotice.CLEAR);
            return;
        }
        // torches the player mined out of the tunnel are not paid back a second time
        // (and bridge lanterns: their positions are kept in the same list)
        int torchesLeft = 0, lanternsLeft = 0;
        for (long tp : be.torchPos) {
            BlockState ts = level.getBlockState(BlockPos.of(tp));
            if (ts.is(Blocks.WALL_TORCH)) torchesLeft++;
            else if (ts.is(Blocks.LANTERN)) lanternsLeft++;
        }
        PathBlocks blocks = PathBlocks.of(level);
        for (int i = be.journal.size() - 1; i >= 0; i--) {
            PathData.Entry e = be.journal.get(i);
            BlockState now = level.getBlockState(e.pos());
            // a block the player built over the path since stays: only what the path laid (or what was dug out of it) goes back
            if (e.laid() != null && !now.isAir() && now.getBlock() != e.laid().getBlock()) continue;
            level.setBlock(e.pos(), e.state(), EXACT);
            blocks.forget(e.pos());
        }
        HousePathIndex index = HousePathIndex.of(level);
        for (long c : be.columns) {
            RoadColumns.mark(level, ChunkPos.getX(c), ChunkPos.getZ(c), false);
            index.set(ChunkPos.getX(c), ChunkPos.getZ(c), false);
        }
        for (ItemStack st : be.paid) {
            ItemStack back = st.copy();
            if (back.is(Items.TORCH)) {
                back.setCount(Math.min(back.getCount(), torchesLeft));
                torchesLeft -= back.getCount();
            } else if (back.is(Items.LANTERN)) {
                back.setCount(Math.min(back.getCount(), lanternsLeft));
                lanternsLeft -= back.getCount();
            }
            if (!back.isEmpty()) Block.popResource(level, stake, back);
        }
        be.forget();
        if (level.getBlockState(stake).is(Stake.BLOCK)) PathRecords.of(level).unmark(stake);
        else PathRecords.of(level).drop(stake);
        note(owner, PathNotice.UNDONE);
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        // surveys get a little more of a tick the server has to spare
        long last = server.getTickTimesNanos()[server.getTickCount() % 100];
        long until = System.nanoTime() + (last < 20_000_000L ? SLICE_IDLE_NANOS : SLICE_NANOS);
        for (PathSearch j : SURVEYS) j.stats.tickTimes.add(server.getTickTimesNanos()[server.getTickCount() % 100]);
        for (Iterator<PathSearch> it = SURVEYS.iterator(); it.hasNext() && System.nanoTime() < until; ) {
            PathSearch j = it.next();
            long t0 = System.nanoTime(), a0 = SurveyStats.allocated();
            SurveyStats.active = j.stats;
            boolean done = j.step(until);
            SurveyStats.active = null;
            long dt = System.nanoTime() - t0;
            j.stats.mainNanos += dt;
            j.stats.mainMaxNanos = Math.max(j.stats.mainMaxNanos, dt);
            j.stats.allocBytes += SurveyStats.allocated() - a0;
            j.stats.ticks++;
            if (done) {
                it.remove();
                t0 = System.nanoTime();
                SurveyStats.active = j.stats;
                finished(j);
                SurveyStats.active = null;
                j.stats.mainNanos += System.nanoTime() - t0;
                j.stats.endNanos = System.nanoTime();
                j.stats.chunksReq = j.requestedCount();
                j.stats.outcome = j.survey != null ? "OK" : String.valueOf(j.failure);
                SurveyStats.last = j.stats;
            }
        }
        if (!DEBOUNCE.isEmpty()) {
            for (Iterator<Map.Entry<BlockPos, Due>> it = DEBOUNCE.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Due due = e.getValue();
                if (due.level.getGameTime() < due.tick) continue;
                it.remove();
                if (anchored(due.level, e.getKey())) survey(due.level, e.getKey(), due.player);
            }
        }
        if (server.getTickCount() % 200 == 0) {
            for (ServerLevel lv : server.getAllLevels()) {
                for (long k : PathRecords.of(lv).older(lv.getGameTime(), COMMIT_TICKS)) {
                    BlockPos p = BlockPos.of(k);
                    if (!lv.hasChunkAt(p) || ROLLOUTS.stream().anyMatch(r -> r.stake.equals(p))) continue;
                    ServerPlayer owner = OWNERS.get(p);
                    if (commit(lv, p) && owner != null) note(owner, PathNotice.COMMITTED);
                }
            }
        }
        for (Iterator<Rollout> it = ROLLOUTS.iterator(); it.hasNext(); ) {
            Rollout r = it.next();
            if (roll(r)) it.remove();
        }
        if ((server.getTickCount() & 7) == 0) {
            for (Iterator<Preview> it = PREVIEWS.values().iterator(); it.hasNext(); ) {
                Preview p = it.next();
                if (p.level.getGameTime() > p.until || p.player == null || p.player.isRemoved() || !anchored(p.level, p.stake)) {
                    it.remove();
                    continue;
                }
                PathData pbe = data(p.level, p.stake);
                if (pbe != null) {
                    // waypoint n shows n dots above its stake, in the colour of the leg that ends there
                    for (int wi = 0; wi < pbe.waypoints.size(); wi++) {
                        BlockPos w = pbe.waypoints.get(wi);
                        DustParticleOptions mark = new DustParticleOptions(LEG_COLORS[wi % LEG_COLORS.length], 1.6f);
                        for (int dot = 0; dot <= wi; dot++) p.level.sendParticles(p.player, mark, true, false, w.getX() + 0.5, w.getY() + 1.3 + dot * 0.45, w.getZ() + 0.5, 1, 0.03, 0.03, 0.03, 0);
                    }
                }
                DustParticleOptions[] legDust = new DustParticleOptions[LEG_COLORS.length];
                for (int li = 0; li < legDust.length; li++) legDust[li] = new DustParticleOptions(LEG_COLORS[li], 1.1f);
                double px = p.player.getX(), pz = p.player.getZ();
                for (int ci = 0; ci < p.cells.size(); ci++) {
                    Cell c = p.cells.get(ci);
                    if (c.bank || c.rail) continue;
                    // cells far from the player would not be seen: no packet for them
                    double dx = c.x + 0.5 - px, dz = c.z + 0.5 - pz;
                    if (dx * dx + dz * dz > PREVIEW_REACH * PREVIEW_REACH) continue;
                    DustParticleOptions dust = legDust[p.legs[ci] % legDust.length];
                    p.level.sendParticles(p.player, dust, true, false, c.x + 0.5, c.y + 1.15 + c.arch / 2.0, c.z + 0.5, 1, 0.15, 0.02, 0.15, 0);
                }
            }
        }
    }

    /** The search is over: show the route and the bill, or say why there is none. */
    private static void finished(PathSearch j) {
        if (j.survey == null) {
            if (j.failure != null) PathNotice.send(j.player, PathNotice.failure(j.failure), j.failureArg, 0, 0, false);
            return;
        }
        Survey sv = j.survey;
        PathData be = j.level.hasChunkAt(j.stake) && anchored(j.level, j.stake) ? (isDoorLike(j.level.getBlockState(j.stake)) ? PathRecords.of(j.level).open(j.stake) : data(j.level, j.stake)) : null;
        if (be != null && be.status != PathData.BUILT) {
            be.status = PathData.PREVIEW;
            be.survey = sv;
            if (j.player != null) PREVIEWS.put(j.stake, new Preview(j.level, j.stake, j.player, sv.cells, j.level.getGameTime() + PREVIEW_TICKS, legsOf(sv.cells, be.waypoints)));
            PathNotice.send(j.player, PathNotice.PREVIEW, sv.length, sv.tunnelCells, be.waypoints.size(), j.freeEnd());
        }
    }

    /**
     * Lays a few cells per tick, nearest the door first, so the path rolls out over about three seconds. A cell whose
     * chunk is not loaded waits. When every cell is down a second pass lays them all again (quietly): it repairs
     * anything the first pass or the game's own updates left behind and clears whatever still stands above the path.
     */
    private static boolean roll(Rollout r) {
        if (r.age[0]++ % PathSearch.TICKET_EVERY == 0)
            for (long ch : r.chunks) r.level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(ChunkPos.getX(ch), ChunkPos.getZ(ch)), 0);
        if (!r.level.hasChunkAt(r.stake)) return false;
        PathData be = data(r.level, r.stake);
        if (be == null) return true;
        int per = Math.max(1, r.cells.size() / 60);
        for (int k = 0; k < per && r.next[0] < r.cells.size(); k++) {
            Cell c = r.cells.get(r.next[0]);
            if (!r.level.hasChunkAt(new BlockPos(c.x, c.y, c.z))) break;
            r.next[0]++;
            lay(r.level, be, c, false, r);
        }
        if (r.next[0] >= r.cells.size()) {
            for (int pass = 0; pass < 2; pass++, r.stamped.clear())
                for (Cell c : r.cells)
                    if (r.level.hasChunkAt(new BlockPos(c.x, c.y, c.z))) lay(r.level, be, c, true, r);
            // the waypoints did their job: they come back as items
            for (BlockPos w : new ArrayList<>(be.waypoints)) if (r.level.getBlockState(w).is(Stake.BLOCK)) r.level.destroyBlock(w, true);
            be.waypoints.clear();
            be.setChanged();
            return true;
        }
        be.setChanged();
        return false;
    }

    /** Above a path cell: natural ground and plants go up to HEADROOM, leaves only up to LEAF_ROOM; trees are never cut (logs stay, canopy stays). */
    public static final int HEADROOM = 5, LEAF_ROOM = 3, TUNNEL_ROOF = Tunnels.ROOF, CUT_MAX = 12;

    /** Share of bare rock (stone, andesite, gravel...) among the ground tops around a step that makes it fieldstone. */
    static final int ROCK_RADIUS = 3, ROCK_SHARE = 35;

    /**
     * The step cells that lie on rocky ground (user, 2026-10-13: mud-brick steps, mixed stone up where the ground is
     * stone). Read before anything is laid, from the natural tops within ROCK_RADIUS, so both passes agree.
     */
    private static java.util.Set<Long> rocky(ServerLevel level, List<Cell> cells) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Cell c : cells) {
            if (c.water || c.bank || c.tun >= 1 || c.stair < 0 && !c.slab) continue;
            int rock = 0, all = 0;
            for (int dx = -ROCK_RADIUS; dx <= ROCK_RADIUS; dx++)
                for (int dz = -ROCK_RADIUS; dz <= ROCK_RADIUS; dz++) {
                    int x = c.x + dx, z = c.z + dz;
                    if (!level.hasChunkAt(new BlockPos(x, c.y, z))) continue;
                    BlockState s = level.getBlockState(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z));
                    all++;
                    if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)) rock++;
                }
            if (all > 0 && rock * 100 >= all * ROCK_SHARE) out.add(key(c.x, c.z));
        }
        return out;
    }

    private static void lay(ServerLevel level, PathData be, Cell c, boolean quiet, Rollout r) {
        java.util.Set<Long> cols = r.cols, rocky = r.rocky;
        int y = c.y;
        long h = c.x * 0x2545F4914F6CDD1DL ^ c.z * 0x9E3779B97F4A7C15L;
        BlockPos at = new BlockPos(c.x, y, c.z);
        if (c.kit) {
            // a kit bridge goes down whole when the rollout reaches its first cell
            if (r.stamped.add(c.tidx)) stampBridge(level, be, r.bridges.get(c.tidx), quiet);
            markPath(level, be, c.x, c.z);
            return;
        }
        if (c.rail) {
            rail(level, be, c);
            return;
        }
        if (c.bank) {
            bank(level, be, c);
            plant(level, be, c.x, c.z, BANK_PLANTS);
            return;
        }
        var biome = level.getBiome(at);
        if (c.tun >= 1) {
            Tunnels.carve(level, be, c, cols);
            markPath(level, be, c.x, c.z);
            return;
        }
        BlockState surface;
        if (c.water && c.arch > 0) {
            archDeck(level, be, c, biome);
            markPath(level, be, c.x, c.z);
            return;
        }
        if (c.water) {
            surface = c.stair >= 0 ? Palette.deckStair(biome).setValue(StairBlock.FACING, DIRS[c.stair]) : Palette.deck(biome);
        } else {
            BlockState natural = level.getBlockState(at);
            BlockState under = level.getBlockState(at.below());
            if (under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.PODZOL) || under.is(Blocks.MYCELIUM)) change(level, be, at.below(), Blocks.DIRT.defaultBlockState());
            // filled down to solid ground: air under a fill, and shallow water beside a path on the shore
            for (int yy = y - 1; yy >= y - 8; yy--) {
                BlockPos q = at.atY(yy);
                BlockState s = level.getBlockState(q);
                if (PaintRules.solid(s) && s.getFluidState().isEmpty()) break;
                change(level, be, q, Blocks.DIRT.defaultBlockState());
            }
            boolean rock = rocky.contains(key(c.x, c.z));
            surface = c.stair >= 0 ? Palette.houseStep(biome, h, false, rock).setValue(StairBlock.FACING, DIRS[c.stair])
                : c.slab ? Palette.houseStep(biome, h, true, rock)
                : c.fringe ? Palette.houseFringe(natural, h) : c.edge ? Palette.houseEdge(biome, h, natural) : Palette.housePath(biome, 1f, h, natural);
        }
        // a column cut deeper than the headroom is cleared to its old ground, so no lip of earth hangs over the path
        int clear = Math.max(HEADROOM, Math.min(CUT_MAX, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x, c.z) - 1 - y));
        change(level, be, at, surface);
        for (int dy = 1; dy <= clear; dy++) {
            BlockPos q = at.above(dy);
            BlockState s = level.getBlockState(q);
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(Stake.BLOCK)) continue;
            if (s.is(BlockTags.LEAVES) ? dy <= LEAF_ROOM : !Retrofit.isCrafted(s)) change(level, be, q, Blocks.AIR.defaultBlockState());
        }
        markPath(level, be, c.x, c.z);
        if (c.edge && !c.water && c.tun < 0) {
            // the verge just outside the bed gets its plants
            int rx = -DZ[c.dir], rz = DX[c.dir];
            for (int s = -1; s <= 1; s += 2)
                if (!cols.contains(key(c.x + s * rx, c.z + s * rz))) plant(level, be, c.x + s * rx, c.z + s * rz, VERGE_PLANTS);
        }
        if (quiet) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, surface), c.x + 0.5, y + 1.05, c.z + 0.5, 6, 0.3, 0.05, 0.3, 0);
        if (((c.x + c.z) & 3) == 0)
            level.playSound(null, at, surface.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5f, 0.9f + ((h >>> 40) & 7) * 0.03f);
    }

    /**
     * A bank column: the ground cut down or filled up to y, topped with its own cover (grass for bare dirt), so the
     * slope beside the path steps one block per column. Plants on the old ground go; trees and built blocks stay.
     */
    private static void bank(ServerLevel level, PathData be, Cell c) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x, c.z) - 1;
        if (top == c.y) return;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(c.x, top, c.z);
        BlockState natural = level.getBlockState(p);
        if (Retrofit.isCrafted(natural) || natural.is(BlockTags.LOGS) || !natural.getFluidState().isEmpty() || natural.is(Stake.BLOCK) || !natural.isSolid()) return;
        BlockState cover = natural.is(Blocks.DIRT) || natural.is(Blocks.COARSE_DIRT) || natural.is(Blocks.ROOTED_DIRT) || natural.is(Blocks.DIRT_PATH)
            || natural.is(Blocks.FARMLAND) ? Blocks.GRASS_BLOCK.defaultBlockState() : natural;
        int lo = Math.min(top, c.y), hi = Math.max(top, c.y);
        // what stands on the old ground (and above the new one) goes first, so nothing is left hanging or half buried
        for (int yy = hi + 2; yy > lo; yy--) {
            BlockState s = level.getBlockState(p.setY(yy));
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || Retrofit.isCrafted(s) || s.is(Stake.BLOCK) || !s.getFluidState().isEmpty()) continue;
            if (yy > top || top > c.y) change(level, be, p.immutable(), Blocks.AIR.defaultBlockState());
        }
        for (int yy = top; yy < c.y; yy++) change(level, be, new BlockPos(c.x, yy, c.z), Blocks.DIRT.defaultBlockState());
        change(level, be, new BlockPos(c.x, c.y, c.z), cover);
    }

    /**
     * A bridge rail column: a stripped-log beam along the bridge at deck height with a fence on it, or (tidx 1) a log
     * post from the water bed up to rail height. Fences take their connections from what stands around them, so the
     * quiet second pass joins them up.
     */
    private static void rail(ServerLevel level, PathData be, Cell c) {
        BlockPos at = new BlockPos(c.x, c.y, c.z);
        var biome = level.getBiome(at);
        // posts in the deck's own log, beams stripped in the darker trim wood (user, 2026-10-13: no spruce posts on oak)
        Palette.Wood frame = Palette.bridgeFrame(biome);
        BlockState log = Palette.woodLog(biome);
        // the beam sits at the deck block's height, the fence on it (an odd arch half: on the deck's slab level)
        int beamY = c.y + c.arch / 2, fenceY = c.y + (c.arch + 2) / 2;
        if (c.tidx >= 1) {
            boolean stone = Palette.bridgeDetail("stone");
            for (int yy = fenceY + (Palette.bridgeDetail("trim") ? 1 : 0); yy >= c.y - 24; yy--) {
                BlockPos q = at.atY(yy);
                BlockState s = level.getBlockState(q);
                if (yy < c.y && PaintRules.solid(s) && s.getFluidState().isEmpty()) break;
                // with "stone" the post stands on a cobble footing that shows a block above the water
                long h = c.x * 31L + yy * 17L + c.z;
                change(level, be, q, stone && yy <= c.y ? ((h & 3) == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState() : log);
            }
        } else {
            Direction.Axis axis = DX[c.dir] != 0 ? Direction.Axis.X : Direction.Axis.Z;
            change(level, be, at.atY(beamY), frame.strippedLog().defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis));
            archUnderside(level, be, c, at.atY(beamY), frame.planks());
            // an open trapdoor flat on the beam's outer face (the inner side is the deck)
            if (Palette.bridgeDetail("trapdoor"))
                for (int s = -1; s <= 1; s += 2) {
                    int ox = -DZ[c.dir] * s, oz = DX[c.dir] * s;
                    BlockState in = level.getBlockState(at.atY(beamY).offset(-ox, 0, -oz));
                    if (!in.is(BlockTags.PLANKS) && !in.is(BlockTags.WOODEN_SLABS) && !in.is(BlockTags.WOODEN_STAIRS)) continue;
                    BlockPos out = at.atY(beamY).offset(ox, 0, oz);
                    if (level.getBlockState(out).isAir() || level.getBlockState(out).is(frame.trapdoor()))
                        change(level, be, out, frame.trapdoor().defaultBlockState().setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, DIRS[PathPlan.dirOf(ox, oz)])
                            .setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, true));
                    break;
                }
            BlockState fence = Palette.woodFence(biome);
            change(level, be, at.atY(fenceY), Block.updateFromNeighbourShapes(fence, level, at.atY(fenceY)));
        }
        int postTop = fenceY + (c.tidx >= 1 && Palette.bridgeDetail("trim") ? 1 : 0);
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos q = at.atY(postTop + dy);
            BlockState s = level.getBlockState(q);
            if (!s.isAir() && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && !Retrofit.isCrafted(s)) change(level, be, q, Blocks.AIR.defaultBlockState());
        }
        // the end posts carry a lantern (paid; refunded only while it still stands)
        if (c.tidx == 2 && Palette.bridgeDetail("lantern")) {
            BlockPos lp = at.atY(postTop + 1);
            change(level, be, lp, Blocks.LANTERN.defaultBlockState());
            if (!be.torchPos.contains(lp.asLong())) be.torchPos.add(lp.asLong());
        }
    }

    /**
     * A deck cell of an arched bridge: a plank arch/2 blocks up, a bottom slab on it for an odd half, the water open
     * under it, an upside-down stair under the step of the underside so it curves, and room to walk above.
     */
    private static void archDeck(ServerLevel level, PathData be, Cell c, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
        BlockPos at = new BlockPos(c.x, c.y + c.arch / 2, c.z);
        BlockState planks = Palette.deck(biome);
        change(level, be, at, planks);
        int top = at.getY();
        if ((c.arch & 1) == 1) {
            change(level, be, at.above(), Palette.woodSlab(biome));
            top++;
        }
        archUnderside(level, be, c, at, planks.getBlock());
        for (int dy = 1; dy <= HEADROOM; dy++) {
            BlockPos q = new BlockPos(c.x, top + dy, c.z);
            BlockState s = level.getBlockState(q);
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(Stake.BLOCK) || Retrofit.isCrafted(s)) continue;
            if (!s.is(BlockTags.LEAVES) || dy <= LEAF_ROOM) change(level, be, q, Blocks.AIR.defaultBlockState());
        }
    }

    /** Under a raised deck block: an upside-down stair toward a neighbour along the bridge whose deck sits a block lower. */
    private static void archUnderside(ServerLevel level, PathData be, Cell c, BlockPos deck, Block wood) {
        if (c.arch < 2) return;
        Block stairs = Palette.stairsOf(wood);
        if (stairs == null) return;
        for (int s = -1; s <= 1; s += 2) {
            int nx = c.x + s * DX[c.dir], nz = c.z + s * DZ[c.dir];
            BlockPos nb = new BlockPos(nx, deck.getY() - 1, nz);
            BlockState ns = level.getBlockState(nb);
            BlockState above = level.getBlockState(nb.above());
            if (!ns.is(BlockTags.PLANKS) && !ns.is(BlockTags.LOGS) || above.is(BlockTags.PLANKS) || above.is(BlockTags.LOGS)) continue;
            Direction toward = s > 0 ? DIRS[c.dir] : DIRS[c.dir].getOpposite();
            change(level, be, deck.below(), stairs.defaultBlockState().setValue(StairBlock.FACING, toward)
                .setValue(StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
            return;
        }
    }

    /**
     * Lays a kit bridge (BridgeKit): trees in its footprint taken out whole, every block of the kit turned to the
     * bridge's direction, posts carried down to the bed, the kit's air clearing what grew or stood there (nothing
     * built), and path ground under the
     * two end cells where the walked line meets the kit.
     */
    private static void stampBridge(ServerLevel level, PathData be, BridgeKit.Bridge br, boolean quiet) {
        net.minecraft.world.level.block.Rotation rot = BridgeKit.rotation(br.dir());
        int len = br.length();
        String[] woods = BridgeKit.woods(level.getBiome(BridgeKit.world(br, BridgeKit.MID, 2, len / 2)));
        clearBridgeTrees(level, be, br);
        for (int z = 0; z < len; z++)
            for (int x = 0; x < BridgeKit.WIDTH; x++) {
                for (int y = 0; y < BridgeKit.height(); y++) {
                    BlockPos p = BridgeKit.world(br, x, y, z);
                    BlockState s = BridgeKit.inWoods(BridgeKit.at(br.spans(), x, y, z), woods);
                    if (s.isAir()) {
                        BlockState old = level.getBlockState(p);
                        if (!old.isAir() && old.getFluidState().isEmpty() && !old.is(BlockTags.LOGS) && !Retrofit.isCrafted(old) && !old.is(Stake.BLOCK))
                            change(level, be, p, Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    change(level, be, p, s.rotate(rot));
                    if (y == 0 && BridgeKit.isPost(s))
                        for (int yy = br.y0() - 1; yy >= br.y0() - 25; yy--) {
                            BlockPos q = p.atY(yy);
                            BlockState under = level.getBlockState(q);
                            if (PaintRules.solid(under) && under.getFluidState().isEmpty()) break;
                            change(level, be, q, s.rotate(rot));
                        }
                }
                // where the line steps off the ground onto the kit: path ground under the deck columns
                if (BridgeKit.halves(br.spans(), z) == 0 && Math.abs(x - BridgeKit.MID) <= 1) {
                    BlockPos g = BridgeKit.world(br, x, -1, z);
                    BlockState natural = level.getBlockState(g);
                    change(level, be, g, Palette.housePath(level.getBiome(g), 1f, g.asLong(), natural));
                    for (int yy = g.getY() - 1; yy >= g.getY() - 8; yy--) {
                        BlockPos q = g.atY(yy);
                        BlockState under = level.getBlockState(q);
                        if (PaintRules.solid(under) && under.getFluidState().isEmpty()) break;
                        change(level, be, q, Blocks.DIRT.defaultBlockState());
                    }
                }
            }
        if (!quiet) {
            BlockPos mid = BridgeKit.world(br, BridgeKit.MID, 2, len / 2);
            level.playSound(null, mid, net.minecraft.sounds.SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
        }
        // lanterns have item value: kept with the torches, refunded only while standing
        for (int z = 0; z < len; z++)
            for (int x = 0; x < BridgeKit.WIDTH; x++)
                for (int y = 0; y < BridgeKit.height(); y++)
                    if (BridgeKit.at(br.spans(), x, y, z).is(Blocks.LANTERN)) {
                        long lp = BridgeKit.world(br, x, y, z).asLong();
                        if (!be.torchPos.contains(lp)) be.torchPos.add(lp);
                    }
    }

    /**
     * Dev: plant a birch inside a one-span bridge's footprint at (x, z) running dir, lay the bridge, count the logs left
     * in the footprint, then take everything back and count what did not return. Prints CR-KITTREE.
     */
    public static String devKitTree(ServerLevel level, int x, int z, int dir) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BridgeKit.Bridge br = new BridgeKit.Bridge(x, z, dir, 1, top + 1);
        BlockPos at = BridgeKit.world(br, BridgeKit.MID + 2, 0, 2);
        BlockPos ground = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        var feature = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.FEATURE)
            .getOrThrow(net.minecraft.data.worldgen.features.TreeFeatures.BIRCH);
        boolean planted = feature.value().place(level, level.getChunkSource().getGenerator(), level.getRandom(), ground);
        java.util.Map<BlockPos, BlockState> before = new java.util.HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-12, -6, -12), at.offset(12, 16, 12))) before.put(p.immutable(), level.getBlockState(p));
        PathData pd = new PathData(() -> {});
        stampBridge(level, pd, br, true);
        int left = 0;
        for (int zz = 0; zz < br.length(); zz++)
            for (int xx = 0; xx < BridgeKit.WIDTH; xx++)
                for (int y = -2; y < BridgeKit.height() + 8; y++) {
                    if (level.getBlockState(BridgeKit.world(br, xx, y, zz)).is(Blocks.BIRCH_LOG)) left++;
                }
        int floating = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-10, -2, -10), at.offset(10, 14, 10))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BIRCH_LEAVES) && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) {
                boolean near = false;
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -4, -4), p.offset(4, 4, 4))) if (level.getBlockState(q).is(BlockTags.LOGS)) near = true;
                if (!near) floating++;
            }
        }
        for (int i = pd.journal.size() - 1; i >= 0; i--) level.setBlock(pd.journal.get(i).pos(), pd.journal.get(i).state(), EXACT);
        int notBack = 0;
        for (var e : before.entrySet()) if (level.getBlockState(e.getKey()) != e.getValue()) notBack++;
        return "CR-KITTREE " + (planted && left == 0 && floating == 0 && notBack == 0 ? "PASS" : "FAIL") + " planted=" + planted + " birchLogsLeftInFootprint=" + left
            + " floatingLeaves=" + floating + " notRestored=" + notBack + " journal=" + pd.journal.size();
    }

    /**
     * Trees standing in a bridge's footprint go whole (user, 2026-10-14: a birch grew through the deck): every log
     * connected to a log in the footprint, if the group carries natural leaves (a tree, not something built), and the
     * natural leaves no remaining log keeps alive. Journaled like everything else, so taking the path up puts them back.
     */
    private static void clearBridgeTrees(ServerLevel level, PathData be, BridgeKit.Bridge br) {
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> logs = new java.util.HashSet<>();
        for (int z = 0; z < br.length(); z++)
            for (int x = 0; x < BridgeKit.WIDTH; x++)
                for (int y = -2; y < BridgeKit.height() + 3; y++) {
                    BlockPos p = BridgeKit.world(br, x, y, z);
                    if (level.getBlockState(p).is(BlockTags.LOGS) && !logs.contains(p)) {
                        // one tree: its logs, 26-connected
                        java.util.Set<BlockPos> tree = new java.util.HashSet<>();
                        boolean leafy = false;
                        todo.add(p);
                        tree.add(p);
                        while (!todo.isEmpty() && tree.size() < 4000) {
                            BlockPos q = todo.poll();
                            for (int dx = -1; dx <= 1; dx++)
                                for (int dy = -1; dy <= 1; dy++)
                                    for (int dz = -1; dz <= 1; dz++) {
                                        BlockPos n = q.offset(dx, dy, dz);
                                        BlockState ns = level.getBlockState(n);
                                        if (ns.is(BlockTags.LOGS)) {
                                            if (tree.add(n)) todo.add(n);
                                        } else if (ns.is(BlockTags.LEAVES) && ns.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                                            && !ns.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) leafy = true;
                                    }
                        }
                        todo.clear();
                        if (leafy && tree.size() < 4000) logs.addAll(tree);
                    }
                }
        if (logs.isEmpty()) return;
        io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-KIT trees cleared from the bridge at " + br.x() + "," + br.z() + ": " + logs.size() + " logs");
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : logs) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        for (BlockPos p : logs) change(level, be, p, Blocks.AIR.defaultBlockState());
        // natural leaves within 6 of a log still standing stay; the rest of the crown goes
        int r = 7;
        java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        for (BlockPos p : BlockPos.betweenClosed(x0 - r, y0 - r, z0 - r, x1 + r, y1 + r, z1 + r))
            if (level.getBlockState(p).is(BlockTags.LOGS)) {
                BlockPos ip = p.immutable();
                dist.put(ip, 0);
                q.add(ip);
            }
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            int d = dist.get(p);
            if (d >= 6) continue;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos n = p.relative(dir);
                if (dist.containsKey(n) || !level.getBlockState(n).is(BlockTags.LEAVES)) continue;
                dist.put(n, d + 1);
                q.add(n);
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(x0 - r, y0 - r, z0 - r, x1 + r, y1 + r, z1 + r)) {
            BlockState s = level.getBlockState(p);
            if (!s.is(BlockTags.LEAVES) || !s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                || s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) || dist.containsKey(p)) continue;
            change(level, be, p.immutable(), Blocks.AIR.defaultBlockState());
        }
    }

    /** Chance (of 1024, times a smooth patch density) of a plant beside the bed and on a bank. */
    static final int VERGE_PLANTS = 330, BANK_PLANTS = 260;

    /** A verge plant on the ground of (x, z) (journaled like every other change, so taking the path up removes it). */
    private static void plant(ServerLevel level, PathData be, int x, int z, int chance) {
        long h = x * 0x2545F4914F6CDD1DL ^ z * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL;
        h ^= h >>> 31;
        if ((int) (h & 1023) >= chance * io.github.profetgit.countryroads.paint.RoadPainter.clump(x, z) / 512) return;
        BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        if (!level.getBlockState(g.above()).isAir() || level.getBlockState(g).is(Stake.BLOCK)) return;
        BlockState plant = Palette.vergePlant(level.getBiome(g.above()), (int) ((h >>> 20) & 1023));
        if (plant == null || !Palette.plantable(plant, level.getBlockState(g))) return;
        BlockState upper = Palette.upperHalf(plant);
        if (upper != null && !level.getBlockState(g.above(2)).isAir()) {
            plant = Blocks.SHORT_GRASS.defaultBlockState();
            upper = null;
        }
        change(level, be, g.above(), plant, true);
        if (upper != null) change(level, be, g.above(2), upper, true);
    }

    /** A path column joins the road columns (other stakes can connect to it) and the index surveys read. */
    private static void markPath(ServerLevel level, PathData be, int x, int z) {
        if (RoadColumns.isRoad(level, x, z)) return;
        RoadColumns.mark(level, x, z, true);
        HousePathIndex.of(level).set(x, z, true);
        be.columns.add(key(x, z));
    }

    public static void boreCost(int per, int fixed) {
        RouteSolver.borePer = per;
        RouteSolver.boreFixed = fixed;
    }

    /** Weight of the survey's distance estimate (0 = plain Dijkstra, for comparing routes with the old search). */
    public static void surveyWeight(double w) {
        RouteSolver.weight = w;
    }

    public static boolean isFrame(Cell c) {
        return Tunnels.isFrame(c);
    }

    static void change(ServerLevel level, PathData be, BlockPos pos, BlockState to) {
        change(level, be, pos, to, false);
    }

    /** `free`: the block drops as usual when broken (verge plants); every other valuable block drops nothing, see PathBlocks. */
    static void change(ServerLevel level, PathData be, BlockPos pos, BlockState to, boolean free) {
        BlockState from = level.getBlockState(pos);
        if (from == to) return;
        be.journal.add(new PathData.Entry(pos.immutable(), from, to));
        level.setBlock(pos, to, EXACT);
        PathBlocks.of(level).put(pos, to, free);
    }

    private static List<Container> sources(ServerLevel level, BlockPos stake, ServerPlayer player) {
        List<Container> out = new ArrayList<>();
        out.add(player.getInventory());
        for (BlockPos q : BlockPos.betweenClosed(stake.offset(-CONTAINER_REACH, -3, -CONTAINER_REACH), stake.offset(CONTAINER_REACH, 3, CONTAINER_REACH))) {
            BlockEntity e = level.getBlockEntity(q);
            if (e instanceof Container c) out.add(c);
        }
        return out;
    }

    private static int count(List<Container> sources, Predicate<ItemStack> want) {
        int n = 0;
        for (Container c : sources)
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && want.test(st)) n += st.getCount();
            }
        return n;
    }

    private static List<ItemStack> take(List<Container> sources, Predicate<ItemStack> want, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        for (Container c : sources)
            for (int i = 0; i < c.getContainerSize() && amount > 0; i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !want.test(st)) continue;
                int n = Math.min(amount, st.getCount());
                taken.add(st.copyWithCount(n));
                st.shrink(n);
                c.setChanged();
                amount -= n;
            }
        return taken;
    }
}
