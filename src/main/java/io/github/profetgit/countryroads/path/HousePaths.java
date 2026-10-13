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
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
 * column), shows the route as a line of particles to the owner and lists the bill. Right-clicking the stake pays
 * (inventory first, then containers within 8 blocks) and the path rolls out from the door to the road. Breaking the
 * stake restores every block it changed and refunds what was paid.
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

    /**
     * One path cell: column, surface block Y, deck over water, shoulder column, the stair facing (index into DIRS, -1 for a
     * flat block), the direction of travel into it, whether it is a straggler beyond the worn band, and the tunnel part
     * (-1 none, 0 open cut, 1 inside the rock, 2 portal) with the index along the roofed run.
     */
    public record Cell(int x, int z, int y, boolean water, boolean edge, int stair, int dir, boolean fringe, int tun, int tidx) {}

    /** A finished survey: cells from the door to the road, the bill, and what the route is made of. */
    public record Survey(List<Cell> cells, int earth, int planks, int torches, int tunnelCells, int length) {}

    public static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    private record Rollout(ServerLevel level, BlockPos stake, List<Cell> cells, int[] next, java.util.Set<Long> chunks, int[] age, java.util.Set<Long> cols) {}

    private record Preview(ServerLevel level, BlockPos stake, ServerPlayer player, List<Cell> cells, long until) {}

    private static final List<PathSearch> SURVEYS = new ArrayList<>();
    private static final List<Rollout> ROLLOUTS = new ArrayList<>();
    private static final Map<BlockPos, Preview> PREVIEWS = new HashMap<>();

    private HousePaths() {}

    /** To the player, or to the log when a test drives the stake without one. */
    private static void tell(ServerPlayer player, Component message, boolean overlay) {
        if (player != null) player.sendSystemMessage(message, overlay);
        else io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STAKE-MSG " + message.getString());
    }

    /** Mod init: the survey's chunk ticket type is registered with the other types. */
    public static void init() {
        PathSearch.init();
    }

    /** Survey chunk tickets held right now (all surveys). */
    public static int surveyTickets() {
        return PathSearch.held;
    }

    public static boolean busy() {
        return !SURVEYS.isEmpty() || !ROLLOUTS.isEmpty();
    }

    static long key(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    /** The stake that placed a path's survey, for messages when a waypoint is added or taken away. */
    private static final Map<BlockPos, ServerPlayer> OWNERS = new HashMap<>();

    /**
     * A stake was placed. Near a stake of the same player whose path is not built yet it is a waypoint of that path
     * (the route is surveyed again through it); otherwise it is the door of a new path.
     */
    public static void placed(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockPos door = player != null && player.isShiftKeyDown() ? null : nearestDoor(level, pos, player);
        if (door == null || !(level.getBlockEntity(pos) instanceof StakeBlockEntity wbe) || !(level.getBlockEntity(door) instanceof StakeBlockEntity dbe)) {
            survey(level, pos, player);
            return;
        }
        wbe.role = StakeBlockEntity.WAYPOINT;
        wbe.door = door;
        dbe.waypoints.add(pos);
        tell(player, Component.translatable("countryroads.stake.waypoint", dbe.waypoints.size()).withStyle(ChatFormatting.AQUA), true);
        survey(level, door, player);
    }

    private static BlockPos nearestDoor(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        List<BlockPos> doors = new ArrayList<>();
        for (PathSearch j : SURVEYS) if (j.level == level && j.player == player) doors.add(j.stake);
        for (Preview p : PREVIEWS.values()) if (p.level == level && p.player == player) doors.add(p.stake);
        for (BlockPos d : doors) {
            if (d.equals(pos) || Math.max(Math.abs(d.getX() - pos.getX()), Math.abs(d.getZ() - pos.getZ())) > WAYPOINT_RADIUS) continue;
            if (!(level.getBlockEntity(d) instanceof StakeBlockEntity be) || be.role != StakeBlockEntity.DOOR || be.status == StakeBlockEntity.BUILT) continue;
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
            return top - below >= 6 ? Terrain.CLOSED : top + Terrain.WET;
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
    }

    public static void survey(ServerLevel level, BlockPos stake, ServerPlayer player) {
        cancelSurveys(stake);
        PREVIEWS.remove(stake);
        List<BlockPos> waypoints = new ArrayList<>();
        if (level.getBlockEntity(stake) instanceof StakeBlockEntity be) {
            be.waypoints.removeIf(w -> !level.getBlockState(w).is(Stake.BLOCK));
            waypoints.addAll(be.waypoints);
            if (be.status == StakeBlockEntity.PREVIEW) {
                be.status = StakeBlockEntity.NONE;
                be.survey = null;
            }
        }
        if (player != null) OWNERS.put(stake, player);
        SURVEYS.add(new PathSearch(level, stake, player, waypoints));
        tell(player, Component.translatable("countryroads.stake.surveying").withStyle(ChatFormatting.GRAY), true);
    }

    public static void use(ServerLevel level, BlockPos stake, ServerPlayer player) {
        if (!(level.getBlockEntity(stake) instanceof StakeBlockEntity be)) return;
        if (be.status == StakeBlockEntity.BUILT) {
            tell(player, Component.translatable("countryroads.stake.built"), true);
            return;
        }
        if (be.status != StakeBlockEntity.PREVIEW || be.survey == null) {
            survey(level, stake, player);
            return;
        }
        Survey s = be.survey;
        List<ItemStack> paid = new ArrayList<>();
        if (player != null && !player.getAbilities().instabuild) {
            List<Container> sources = sources(level, stake, player);
            int earthHave = count(sources, st -> EARTH.contains(st.getItem())), planksHave = count(sources, st -> st.is(ItemTags.PLANKS));
            int torchesHave = count(sources, st -> st.is(Items.TORCH));
            if (earthHave < s.earth || planksHave < s.planks || torchesHave < s.torches) {
                tell(player, Component.translatable("countryroads.stake.missing", Math.max(0, s.earth - earthHave),
                    Math.max(0, s.planks - planksHave), Math.max(0, s.torches - torchesHave)).withStyle(ChatFormatting.RED), false);
                return;
            }
            paid.addAll(take(sources, st -> EARTH.contains(st.getItem()), s.earth));
            paid.addAll(take(sources, st -> st.is(ItemTags.PLANKS), s.planks));
            paid.addAll(take(sources, st -> st.is(Items.TORCH), s.torches));
        }
        be.paid.clear();
        be.paid.addAll(paid);
        be.status = StakeBlockEntity.BUILT;
        be.survey = null;
        be.setChanged();
        PREVIEWS.remove(stake);
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (Cell c : s.cells) {
            chunks.add(ChunkPos.pack(c.x >> 4, c.z >> 4));
            cols.add(key(c.x, c.z));
        }
        ROLLOUTS.add(new Rollout(level, stake, s.cells, new int[]{0}, chunks, new int[]{0}, cols));
        tell(player, Component.translatable("countryroads.stake.building", s.cells.size()), true);
    }

    /** The stake is being broken: put back every block the path changed and give back the materials. */
    public static void takeUp(ServerLevel level, BlockPos stake) {
        PREVIEWS.remove(stake);
        cancelSurveys(stake);
        ROLLOUTS.removeIf(r -> r.stake.equals(stake));
        if (level.getBlockEntity(stake) instanceof StakeBlockEntity first) {
            if (first.role == StakeBlockEntity.WAYPOINT) {
                // a waypoint taken away: the door surveys again without it
                BlockPos door = first.door;
                first.role = StakeBlockEntity.DOOR;
                if (door != null && level.getBlockEntity(door) instanceof StakeBlockEntity dbe && dbe.status != StakeBlockEntity.BUILT) {
                    dbe.waypoints.remove(stake);
                    survey(level, door, OWNERS.get(door));
                }
                return;
            }
            for (BlockPos w : new ArrayList<>(first.waypoints)) if (level.getBlockState(w).is(Stake.BLOCK)) level.destroyBlock(w, true);
            first.waypoints.clear();
            OWNERS.remove(stake);
        }
        if (!(level.getBlockEntity(stake) instanceof StakeBlockEntity be) || be.status != StakeBlockEntity.BUILT) return;
        // torches the player mined out of the tunnel are not paid back a second time
        int torchesLeft = 0;
        for (long tp : be.torchPos) if (level.getBlockState(BlockPos.of(tp)).is(Blocks.WALL_TORCH)) torchesLeft++;
        for (int i = be.journal.size() - 1; i >= 0; i--) {
            StakeBlockEntity.Entry e = be.journal.get(i);
            level.setBlock(e.pos(), e.state(), EXACT);
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
            }
            if (!back.isEmpty()) Block.popResource(level, stake, back);
        }
        be.torchPos.clear();
        be.journal.clear();
        be.paid.clear();
        be.columns.clear();
        be.status = StakeBlockEntity.NONE;
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
        for (Iterator<Rollout> it = ROLLOUTS.iterator(); it.hasNext(); ) {
            Rollout r = it.next();
            if (roll(r)) it.remove();
        }
        if ((server.getTickCount() & 7) == 0) {
            for (Iterator<Preview> it = PREVIEWS.values().iterator(); it.hasNext(); ) {
                Preview p = it.next();
                if (p.level.getGameTime() > p.until || p.player == null || p.player.isRemoved() || !p.level.getBlockState(p.stake).is(Stake.BLOCK)) {
                    it.remove();
                    continue;
                }
                if (p.level.getBlockEntity(p.stake) instanceof StakeBlockEntity pbe) {
                    DustParticleOptions blue = new DustParticleOptions(0x4FC3F7, 1.4f);
                    for (BlockPos w : pbe.waypoints)
                        for (int dy = 1; dy <= 4; dy++) p.level.sendParticles(p.player, blue, true, false, w.getX() + 0.5, w.getY() + dy * 0.5, w.getZ() + 0.5, 1, 0.05, 0.1, 0.05, 0);
                }
                DustParticleOptions dust = new DustParticleOptions(0xF5D76E, 1.1f);
                double px = p.player.getX(), pz = p.player.getZ();
                for (Cell c : p.cells) {
                    // cells far from the player would not be seen: no packet for them
                    double dx = c.x + 0.5 - px, dz = c.z + 0.5 - pz;
                    if (dx * dx + dz * dz > PREVIEW_REACH * PREVIEW_REACH) continue;
                    p.level.sendParticles(p.player, dust, true, false, c.x + 0.5, c.y + 1.15, c.z + 0.5, 1, 0.15, 0.02, 0.15, 0);
                }
            }
        }
    }

    /** The search is over: show the route and the bill, or say why there is none. */
    private static void finished(PathSearch j) {
        if (j.survey == null) {
            if ("countryroads.stake.on_road".equals(j.failure)) tell(j.player, Component.translatable(j.failure), true);
            else if (j.failure != null) tell(j.player, Component.translatable(j.failure, j.failureArg).withStyle(ChatFormatting.RED), false);
            return;
        }
        Survey sv = j.survey;
        if (j.level.hasChunkAt(j.stake) && j.level.getBlockEntity(j.stake) instanceof StakeBlockEntity be && be.status != StakeBlockEntity.BUILT) {
            be.status = StakeBlockEntity.PREVIEW;
            be.survey = sv;
            if (j.player != null) PREVIEWS.put(j.stake, new Preview(j.level, j.stake, j.player, sv.cells, j.level.getGameTime() + PREVIEW_TICKS));
            tell(j.player, Component.translatable("countryroads.stake.preview", sv.length, sv.tunnelCells, sv.earth, sv.planks, sv.torches).withStyle(ChatFormatting.YELLOW), false);
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
        if (!(r.level.getBlockEntity(r.stake) instanceof StakeBlockEntity be)) return true;
        int per = Math.max(1, r.cells.size() / 60);
        for (int k = 0; k < per && r.next[0] < r.cells.size(); k++) {
            Cell c = r.cells.get(r.next[0]);
            if (!r.level.hasChunkAt(new BlockPos(c.x, c.y, c.z))) break;
            r.next[0]++;
            lay(r.level, be, c, false, r.cols);
        }
        if (r.next[0] >= r.cells.size()) {
            for (int pass = 0; pass < 2; pass++)
                for (Cell c : r.cells)
                    if (r.level.hasChunkAt(new BlockPos(c.x, c.y, c.z))) lay(r.level, be, c, true, r.cols);
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
    static final int HEADROOM = 5, LEAF_ROOM = 3, TUNNEL_ROOF = Tunnels.ROOF;

    private static void lay(ServerLevel level, StakeBlockEntity be, Cell c, boolean quiet, java.util.Set<Long> cols) {
        int y = c.y;
        long h = c.x * 0x2545F4914F6CDD1DL ^ c.z * 0x9E3779B97F4A7C15L;
        BlockPos at = new BlockPos(c.x, y, c.z);
        var biome = level.getBiome(at);
        if (c.tun >= 1) {
            Tunnels.carve(level, be, c, cols);
            markPath(level, be, c.x, c.z);
            return;
        }
        BlockState surface;
        if (c.water) {
            surface = c.stair >= 0 ? Palette.deckStair(biome).setValue(StairBlock.FACING, DIRS[c.stair]) : Palette.deck(biome);
        } else {
            BlockState natural = level.getBlockState(at);
            BlockState under = level.getBlockState(at.below());
            if (under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.PODZOL) || under.is(Blocks.MYCELIUM)) change(level, be, at.below(), Blocks.DIRT.defaultBlockState());
            for (int yy = y - 1; yy >= y - 8; yy--) {
                BlockPos q = at.atY(yy);
                BlockState s = level.getBlockState(q);
                if (PaintRules.solid(s) && s.getFluidState().isEmpty()) break;
                if (!s.getFluidState().isEmpty()) break;
                change(level, be, q, Blocks.DIRT.defaultBlockState());
            }
            surface = c.stair >= 0
                ? Palette.deckStair(biome).setValue(StairBlock.FACING, DIRS[c.stair])
                : c.fringe ? Palette.houseFringe(natural, h) : c.edge ? Palette.houseEdge(biome, h, natural) : Palette.housePath(biome, 1f, h, natural);
        }
        change(level, be, at, surface);
        for (int dy = 1; dy <= HEADROOM; dy++) {
            BlockPos q = at.above(dy);
            BlockState s = level.getBlockState(q);
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(Stake.BLOCK)) continue;
            if (s.is(BlockTags.LEAVES) ? dy <= LEAF_ROOM : !Retrofit.isCrafted(s)) change(level, be, q, Blocks.AIR.defaultBlockState());
        }
        markPath(level, be, c.x, c.z);
        if (quiet) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, surface), c.x + 0.5, y + 1.05, c.z + 0.5, 6, 0.3, 0.05, 0.3, 0);
        if (((c.x + c.z) & 3) == 0)
            level.playSound(null, at, surface.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5f, 0.9f + ((h >>> 40) & 7) * 0.03f);
    }

    /** A path column joins the road columns (other stakes can connect to it) and the index surveys read. */
    private static void markPath(ServerLevel level, StakeBlockEntity be, int x, int z) {
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

    static void change(ServerLevel level, StakeBlockEntity be, BlockPos pos, BlockState to) {
        BlockState from = level.getBlockState(pos);
        if (from == to) return;
        be.journal.add(new StakeBlockEntity.Entry(pos.immutable(), from));
        level.setBlock(pos, to, EXACT);
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
