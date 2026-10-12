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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * House paths. Placing a Surveyor's Stake runs a survey (time-sliced search over the real terrain to the nearest road
 * column), shows the route as a line of particles to the owner and lists the bill. Right-clicking the stake pays
 * (inventory first, then containers within 8 blocks) and the path rolls out from the door to the road. Breaking the
 * stake restores every block it changed and refunds what was paid.
 */
public final class HousePaths {
    static final int RADIUS = 160, MAX_EXPANSIONS = 150_000, PREVIEW_TICKS = 2400, CONTAINER_REACH = 8;
    static final long SLICE_NANOS = 2_000_000;
    /**
     * Path blocks go in (and come back out) exactly, without neighbour updates: a full update would break the top half
     * of tall grass before it is journaled, and turn path under a slab into dirt.
     */
    static final int EXACT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final Set<Item> EARTH = Set.of(Items.DIRT, Items.COARSE_DIRT, Items.GRAVEL, Items.SAND, Items.RED_SAND, Items.PACKED_MUD,
        Items.MUD, Items.ROOTED_DIRT, Items.COBBLESTONE, Items.DIRT_PATH);

    /** One path cell: column, planned surface (half blocks), whether it is a deck over water. */
    public record Cell(int x, int z, double y, boolean water, boolean edge) {}

    /** A finished survey: cells from the door to the road, and the bill. */
    public record Survey(List<Cell> cells, int earth, int planks) {}

    private static final class Job {
        final ServerLevel level;
        final BlockPos stake;
        final ServerPlayer player;
        final int sx, sz;
        final PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        final Long2IntOpenHashMap cost = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap height = new Long2IntOpenHashMap();
        int expansions;

        Job(ServerLevel level, BlockPos stake, ServerPlayer player) {
            this.level = level;
            this.stake = stake;
            this.player = player;
            this.sx = stake.getX();
            this.sz = stake.getZ();
            cost.defaultReturnValue(Integer.MAX_VALUE);
        }
    }

    private record Rollout(ServerLevel level, BlockPos stake, List<Cell> cells, int[] next) {}

    private record Preview(ServerLevel level, BlockPos stake, ServerPlayer player, List<Cell> cells, long until) {}

    private static final List<Job> SURVEYS = new ArrayList<>();
    private static final List<Rollout> ROLLOUTS = new ArrayList<>();
    private static final Map<BlockPos, Preview> PREVIEWS = new HashMap<>();

    private HousePaths() {}

    /** To the player, or to the log when a test drives the stake without one. */
    private static void tell(ServerPlayer player, Component message, boolean overlay) {
        if (player != null) player.sendSystemMessage(message, overlay);
        else io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STAKE-MSG " + message.getString());
    }

    public static boolean busy() {
        return !SURVEYS.isEmpty() || !ROLLOUTS.isEmpty();
    }

    private static long key(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    public static void survey(ServerLevel level, BlockPos stake, ServerPlayer player) {
        SURVEYS.removeIf(j -> j.stake.equals(stake));
        Job j = new Job(level, stake, player);
        long k = key(stake.getX(), stake.getZ());
        j.cost.put(k, 0);
        j.height.put(k, stake.getY() - 1);
        j.open.add(new long[]{0, k});
        SURVEYS.add(j);
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
            if (earthHave < s.earth || planksHave < s.planks) {
                tell(player, Component.translatable("countryroads.stake.missing",
                    Math.max(0, s.earth - earthHave), Math.max(0, s.planks - planksHave)).withStyle(ChatFormatting.RED), false);
                return;
            }
            paid.addAll(take(sources, st -> EARTH.contains(st.getItem()), s.earth));
            paid.addAll(take(sources, st -> st.is(ItemTags.PLANKS), s.planks));
        }
        be.paid.clear();
        be.paid.addAll(paid);
        be.status = StakeBlockEntity.BUILT;
        be.survey = null;
        be.setChanged();
        PREVIEWS.remove(stake);
        ROLLOUTS.add(new Rollout(level, stake, s.cells, new int[]{0}));
        tell(player, Component.translatable("countryroads.stake.building", s.cells.size()), true);
    }

    /** The stake is being broken: put back every block the path changed and give back the materials. */
    public static void takeUp(ServerLevel level, BlockPos stake) {
        PREVIEWS.remove(stake);
        SURVEYS.removeIf(j -> j.stake.equals(stake));
        ROLLOUTS.removeIf(r -> r.stake.equals(stake));
        if (!(level.getBlockEntity(stake) instanceof StakeBlockEntity be) || be.status != StakeBlockEntity.BUILT) return;
        for (int i = be.journal.size() - 1; i >= 0; i--) {
            StakeBlockEntity.Entry e = be.journal.get(i);
            level.setBlock(e.pos(), e.state(), EXACT);
        }
        for (long c : be.columns) RoadColumns.mark(level, ChunkPos.getX(c), ChunkPos.getZ(c), false);
        for (ItemStack st : be.paid) Block.popResource(level, stake, st.copy());
        be.journal.clear();
        be.paid.clear();
        be.columns.clear();
        be.status = StakeBlockEntity.NONE;
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        long until = System.nanoTime() + SLICE_NANOS;
        for (Iterator<Job> it = SURVEYS.iterator(); it.hasNext() && System.nanoTime() < until; ) {
            Job j = it.next();
            if (step(j, until)) it.remove();
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
                DustParticleOptions dust = new DustParticleOptions(0xF5D76E, 1.1f);
                for (Cell c : p.cells)
                    p.level.sendParticles(p.player, dust, true, false, c.x + 0.5, Math.floor(c.y) + 1.15, c.z + 0.5, 1, 0.15, 0.02, 0.15, 0);
            }
        }
    }

    /** Dijkstra over terrain columns; true when finished (found or failed). */
    private static boolean step(Job j, long until) {
        ServerLevel level = j.level;
        while (!j.open.isEmpty()) {
            if (System.nanoTime() > until) return false;
            long[] n = j.open.poll();
            long k = n[1];
            int c = (int) n[0];
            if (c != j.cost.get(k)) continue;
            int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
            if (!(x == j.sx && z == j.sz) && RoadColumns.isOpenRoad(level, x, z)) {
                finish(j, k);
                return true;
            }
            if (++j.expansions > MAX_EXPANSIONS) break;
            int y = j.height.get(k);
            for (Direction d : Direction.Plane.HORIZONTAL) step(j, x, z, y, c, d.getStepX(), d.getStepZ(), 10);
            for (int dx = -1; dx <= 1; dx += 2)
                for (int dz = -1; dz <= 1; dz += 2)
                    if (passable(j, x + dx, z) >= -1000 && passable(j, x, z + dz) >= -1000) step(j, x, z, y, c, dx, dz, 14);
        }
        io.github.profetgit.countryroads.CountryRoads.LOG.debug("Survey from {} found no road after {} columns", j.stake, j.expansions);
        if (j.player == null) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-STAKE-DEBUG expansions=" + j.expansions);
        tell(j.player, Component.translatable("countryroads.stake.no_road", RADIUS).withStyle(ChatFormatting.RED), false);
        return true;
    }

    private static void step(Job j, int x, int z, int y, int c, int dx, int dz, int base) {
        int nx = x + dx, nz = z + dz;
        if (Math.max(Math.abs(nx - j.sx), Math.abs(nz - j.sz)) > RADIUS) return;
        int ny = passable(j, nx, nz);
        if (ny < -1000) return;
        boolean wet = ny >= 100000;
        int walkTo = wet ? ny - 100000 + 1 : ny, walkFrom = y >= 100000 ? y - 100000 + 1 : y;
        if (Math.abs(walkTo - walkFrom) > 1) return;
        int cost = c + base + 8 * Math.abs(walkTo - walkFrom) + (wet ? 30 : 0);
        long k = key(nx, nz);
        if (cost >= j.cost.get(k)) return;
        j.cost.put(k, cost);
        j.height.put(k, ny);
        j.parent.put(k, key(x, z));
        j.open.add(new long[]{cost, k});
    }

    /**
     * Surface Y of a column the path may use, +100000 when it is water (a deck goes on top), below -1000 when the
     * column is closed: unloaded, a tree, lava, or something built.
     */
    private static int passable(Job j, int x, int z) {
        ServerLevel level = j.level;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return -100000;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState s = level.getBlockState(new BlockPos(x, top, z));
        if (s.is(Blocks.WATER)) {
            int below = top;
            while (below > level.getMinY() && level.getBlockState(new BlockPos(x, below, z)).is(Blocks.WATER) && top - below < 6) below--;
            return top - below >= 6 ? -100000 : top + 100000;
        }
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LOGS) || (Retrofit.isCrafted(s) && !RoadColumns.isRoad(level, x, z))) return -100000;
        if (x == j.sx && z == j.sz) return j.stake.getY() - 1;
        return top;
    }

    private static void finish(Job j, long roadKey) {
        List<long[]> line = new ArrayList<>();
        long k = j.parent.get(roadKey);
        while (true) {
            if (k == key(j.sx, j.sz)) break;
            line.add(new long[]{k, j.height.get(k)});
            if (!j.parent.containsKey(k)) break;
            k = j.parent.get(k);
        }
        java.util.Collections.reverse(line);
        if (line.isEmpty()) {
            tell(j.player, Component.translatable("countryroads.stake.on_road"), true);
            return;
        }
        int n = line.size();
        double[] y = new double[n];
        boolean[] wet = new boolean[n];
        for (int i = 0; i < n; i++) {
            int h = (int) line.get(i)[1];
            wet[i] = h >= 100000;
            y[i] = wet[i] ? h - 100000 + 1 : h;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < n; i++) if (!wet[i]) y[i] = Math.max(y[i - 1] - 0.5, Math.min(y[i - 1] + 0.5, y[i]));
            for (int i = n - 2; i >= 0; i--) if (!wet[i]) y[i] = Math.max(y[i + 1] - 0.5, Math.min(y[i + 1] + 0.5, y[i]));
        }
        List<Cell> cells = new ArrayList<>();
        java.util.HashSet<Long> used = new java.util.HashSet<>();
        int earth = 0, planks = 0;
        for (int i = 0; i < n; i++) {
            int x = ChunkPos.getX(line.get(i)[0]), z = ChunkPos.getZ(line.get(i)[0]);
            double yy = Math.round(y[i] * 2) / 2.0;
            cells.add(new Cell(x, z, yy, wet[i], false));
            used.add(line.get(i)[0]);
            if (wet[i]) planks++;
            else earth++;
            // width 2 except the last three cells at the door
            if (i >= 3 && i + 1 < n) {
                int px = ChunkPos.getX(line.get(i + 1)[0]) - ChunkPos.getX(line.get(i - 1)[0]), pz = ChunkPos.getZ(line.get(i + 1)[0]) - ChunkPos.getZ(line.get(i - 1)[0]);
                int rx = Integer.signum(-pz), rz = Integer.signum(px);
                if (Math.abs(rx) + Math.abs(rz) == 2) rz = 0;
                long side = key(x + rx, z + rz);
                if (!used.contains(side) && !wet[i]) {
                    int sh = passable(j, x + rx, z + rz);
                    if (sh > -1000 && sh < 100000 && Math.abs(sh - yy) <= 1 && !RoadColumns.isRoad(j.level, x + rx, z + rz)) {
                        cells.add(new Cell(x + rx, z + rz, yy, false, true));
                        used.add(side);
                        earth++;
                    }
                }
            }
        }
        Survey s = new Survey(cells, (earth + 1) / 2, planks);
        if (j.level.getBlockEntity(j.stake) instanceof StakeBlockEntity be && be.status != StakeBlockEntity.BUILT) {
            be.status = StakeBlockEntity.PREVIEW;
            be.survey = s;
            if (j.player != null) PREVIEWS.put(j.stake, new Preview(j.level, j.stake, j.player, cells, j.level.getGameTime() + PREVIEW_TICKS));
            tell(j.player, Component.translatable("countryroads.stake.preview", n, s.earth, s.planks).withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /** Lays a few cells per tick, nearest the door first, so the path rolls out over about three seconds. */
    private static boolean roll(Rollout r) {
        if (!(r.level.getBlockEntity(r.stake) instanceof StakeBlockEntity be)) return true;
        int per = Math.max(1, r.cells.size() / 60);
        for (int k = 0; k < per && r.next[0] < r.cells.size(); k++) {
            Cell c = r.cells.get(r.next[0]++);
            lay(r.level, be, c);
        }
        be.setChanged();
        return r.next[0] >= r.cells.size();
    }

    private static void lay(ServerLevel level, StakeBlockEntity be, Cell c) {
        int top = (int) Math.floor(c.y);
        boolean half = c.y - top >= 0.5;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        long h = c.x * 0x2545F4914F6CDD1DL ^ c.z * 0x9E3779B97F4A7C15L;
        BlockState surface;
        if (c.water) {
            surface = Palette.deck(level.getBiome(p.set(c.x, top, c.z)));
        } else {
            BlockState natural = level.getBlockState(p.set(c.x, top, c.z));
            surface = Palette.housePath(level.getBiome(p), c.edge ? 0.3f : 1f, h, natural);
            if (!PaintRules.solid(level.getBlockState(p.set(c.x, top - 1, c.z)))) change(level, be, p.immutable(), Blocks.DIRT.defaultBlockState());
        }
        BlockPos at = new BlockPos(c.x, top, c.z);
        change(level, be, at, surface);
        if (half) change(level, be, at.above(), Palette.houseStep(level.getBiome(at)).setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        for (int dy = half ? 2 : 1; dy <= 2; dy++) {
            BlockPos q = at.above(dy);
            BlockState s = level.getBlockState(q);
            if (!s.isAir() && (s.canBeReplaced() || s.is(Blocks.SNOW))) change(level, be, q, Blocks.AIR.defaultBlockState());
        }
        if (!RoadColumns.isRoad(level, c.x, c.z)) {
            RoadColumns.mark(level, c.x, c.z, true);
            be.columns.add(key(c.x, c.z));
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, surface), c.x + 0.5, top + 1.05, c.z + 0.5, 6, 0.3, 0.05, 0.3, 0);
        if (((c.x + c.z) & 3) == 0)
            level.playSound(null, at, surface.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5f, 0.9f + ((h >>> 40) & 7) * 0.03f);
    }

    private static void change(ServerLevel level, StakeBlockEntity be, BlockPos pos, BlockState to) {
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
