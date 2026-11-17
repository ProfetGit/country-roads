package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.world.RoadColumns;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * `countryroads testtown [lots]`: a dense player town around the command position, for watching a house path find its
 * way out to the road. Lots on a grid with 2-4 wide alleys: plank cabins, concrete, stone-brick and terracotta houses,
 * fenced fields, a polished plaza, a storage yard behind iron bars, market stalls, empty lots, and a rail line across
 * the town with one level crossing. Lots within 4 blocks of a road stay empty. The middle lot is a cabin whose door
 * faces south: use the Surveyor's Stake on it. One lot per tick.
 */
final class TestTown {
    private TestTown() {}

    private record Lot(int x0, int z0, int w, int d, int type, boolean start) {}

    static final int CABIN = 0, CONCRETE = 1, STONE = 2, TERRACOTTA = 3, FIELD = 4, PLAZA = 5, YARD = 6, MARKET = 7, EMPTY = 8;
    /** Mostly houses, one empty lot in fourteen. */
    private static final int[] PICK = {CABIN, CABIN, CONCRETE, CONCRETE, STONE, STONE, TERRACOTTA, FIELD, FIELD, PLAZA, YARD, MARKET, MARKET, EMPTY};
    private static final String[] NAMES = {"cabin", "concrete", "stone", "terracotta", "field", "plaza", "yard", "market", "empty"};

    /** The last town's blocks above the levelled ground (and its paved floors), by position, for `testtown check`. */
    private static final class Last {
        static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.state.BlockState> PLACED = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap LOT_OF = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        static final List<Lot> BUILT = new ArrayList<>();
        /** Stake spots two out from each house door of the last town: x, z, house type. */
        static final List<int[]> STAKES = new ArrayList<>();
    }

    /** `testtown stake <i>`: a kept stake test from the i-th house door (the town's own order; terracotta houses first). */
    static int stake(CommandSourceStack src, int i) {
        // queued like the town itself, so it runs after the town is built (the harness sends every command at once)
        java.util.function.BooleanSupplier[] test = {null};
        DevCommands.queue(() -> {
            if (test[0] == null) {
                List<int[]> order = new ArrayList<>(Last.STAKES);
                order.sort(java.util.Comparator.comparingInt(a -> a[2] == TERRACOTTA ? 0 : 1));
                if (i >= order.size()) {
                    CountryRoads.LOG.info("CR-TOWN-STAKE " + i + " none (" + order.size() + " doors)");
                    return true;
                }
                int[] a = order.get(i);
                CountryRoads.LOG.info("CR-TOWN-STAKE " + i + " " + NAMES[a[2]] + " " + a[0] + " " + a[1]);
                test[0] = DevCommands.inline(() -> StakeTest.run(src, a[0], a[1], true));
            }
            return test[0].getAsBoolean();
        });
        return 1;
    }

    /** Every block the last town placed that is not plain ground, compared with the world now: what a path changed. */
    static int check(CommandSourceStack src) {
        DevCommands.queue(() -> {
            checkNow(src);
            return true;
        });
        return 1;
    }

    private static void checkNow(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        int[] changed = new int[Last.BUILT.size()];
        String[] first = new String[Last.BUILT.size()];
        for (var e : Last.PLACED.long2ObjectEntrySet()) {
            BlockPos p = BlockPos.of(e.getLongKey());
            var now = level.getBlockState(p);
            // crops grow and farmland dries or gets wet: only the block itself counts
            if (now.getBlock() == e.getValue().getBlock()) continue;
            int lot = Last.LOT_OF.get(e.getLongKey());
            if (changed[lot]++ == 0) first[lot] = p.getX() + " " + p.getY() + " " + p.getZ() + " " + name(e.getValue()) + "->" + name(now);
        }
        int lots = 0, blocks = 0;
        for (int i = 0; i < Last.BUILT.size(); i++) {
            if (changed[i] == 0) continue;
            Lot l = Last.BUILT.get(i);
            lots++;
            blocks += changed[i];
            CountryRoads.LOG.info("CR-TOWN-CHANGED " + NAMES[l.type()] + " " + l.x0() + "," + l.z0() + " blocks=" + changed[i] + " first " + first[i]);
        }
        String line = "CR-TOWN-CHECK " + (Last.PLACED.isEmpty() ? "FAIL no town" : lots == 0 ? "PASS" : "FAIL") + " lots=" + Last.BUILT.size() + " blocks=" + Last.PLACED.size()
            + " changedLots=" + lots + " changedBlocks=" + blocks;
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static String name(net.minecraft.world.level.block.state.BlockState s) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    /** Remembers a built lot's blocks: everything over its ground, and its floor where that is not grass or dirt. */
    private static void remember(ServerLevel level, Lot l, int base) {
        int idx = Last.BUILT.size();
        Last.BUILT.add(l);
        for (int x = l.x0(); x < l.x0() + l.w(); x++)
            for (int z = l.z0(); z < l.z0() + l.d(); z++)
                for (int y = base; y <= base + 6; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var s = level.getBlockState(p);
                    if (s.isAir() || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)) continue;
                    Last.PLACED.put(p.asLong(), s);
                    Last.LOT_OF.put(p.asLong(), idx);
                }
    }

    static int run(CommandSourceStack src, int half) {
        Last.PLACED.clear();
        Last.LOT_OF.clear();
        Last.BUILT.clear();
        Last.STAKES.clear();
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        List<Lot> lots = new ArrayList<>();
        int z = at.getZ() - half * 11;
        for (int j = -half; j < half; j++) {
            int d = j == -1 ? 9 : 8, x = at.getX() - half * 12;
            for (int i = -half; i < half; i++) {
                int w = (i + j & 1) == 0 ? 9 : 8;
                long h = mix(i * 31 + 7, j * 17 + 3);
                int type = i == -1 && j == -1 ? CABIN : PICK[(int) Math.floorMod(h, PICK.length)];
                lots.add(new Lot(x, z, w, d, type, i == -1 && j == -1));
                x += w + 2 + (int) Math.floorMod(h >>> 8, 2);
            }
            z += d + 2 + (j == 0 ? 2 : (int) Math.floorMod(mix(j, 99) >>> 4, 2));
        }
        int railZ = at.getZ() + 1, x0 = lots.getFirst().x0(), x1 = lots.stream().mapToInt(l -> l.x0() + l.w()).max().orElse(at.getX());
        int[] next = {0}, built = {0}, skipped = {0};
        BlockPos[] door = new BlockPos[1];
        DevCommands.queue(() -> {
            if (next[0] < lots.size()) {
                Lot l = lots.get(next[0]++);
                if (nearRoad(level, l) && !l.start()) {
                    skipped[0]++;
                    CountryRoads.LOG.info("CR-TOWN-LOT skipped " + l.x0() + "," + l.z0());
                }
                else {
                    int base = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, l.x0() + l.w() / 2, l.z0() + l.d() / 2) - 1;
                    BlockPos d = build(level, l);
                    remember(level, l, base);
                    if (d != null && l.start()) door[0] = d;
                    if (d != null) {
                        // a stake two out from the door, for `staketest X Z`
                        Direction f = level.getBlockState(d).getValue(DoorBlock.FACING);
                        Last.STAKES.add(new int[]{d.getX() + 2 * f.getStepX(), d.getZ() + 2 * f.getStepZ(), l.type()});
                        CountryRoads.LOG.info("CR-TOWN-DOOR " + NAMES[l.type()] + " " + d.getX() + " " + d.getY() + " " + d.getZ() + " stake " + (d.getX() + 2 * f.getStepX()) + " " + (d.getZ() + 2 * f.getStepZ()));
                    }
                    CountryRoads.LOG.info("CR-TOWN-LOT " + NAMES[l.type()] + " " + l.x0() + "," + l.z0() + " " + l.w() + "x" + l.d() + " base " + base);
                    built[0]++;
                }
                return false;
            }
            // the rail line runs along the alley between the two middle rows, with one level crossing
            int gap = x0 + (x1 - x0) / 3;
            for (int x = x0; x <= x1; x++) {
                if (x >= gap && x < gap + 2 || RoadColumns.isRoad(level, x, railZ)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, railZ);
                if (!level.getBlockState(new BlockPos(x, y - 1, railZ)).isSolid()) continue;
                level.setBlock(new BlockPos(x, y, railZ), Blocks.RAIL.defaultBlockState(), 3);
            }
            String line = "CR-TOWN lots=" + built[0] + " skipped=" + skipped[0] + " (near a road) area=" + x0 + ".." + x1 + " x " + lots.getFirst().z0() + ".."
                + (lots.getLast().z0() + lots.getLast().d()) + " rail z=" + railZ + " crossing x=" + gap + ".." + (gap + 1)
                + (door[0] == null ? " no door" : " door " + door[0].getX() + " " + door[0].getY() + " " + door[0].getZ() + " (faces south)");
            CountryRoads.LOG.info(line);
            src.sendSuccess(() -> Component.literal(line), false);
            return true;
        });
        return 1;
    }

    // coloured blocks are one ColorCollection each since 26.x
    private static BlockState concrete(boolean white) {
        //? if >=26.2 {
        return Blocks.CONCRETE.pick(white ? net.minecraft.world.item.DyeColor.WHITE : net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
        //?} else {
        /*return (white ? Blocks.WHITE_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState();
        *///?}
    }

    private static BlockState whiteTerracotta() {
        //? if >=26.2 {
        return Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
        //?} else {
        /*return Blocks.WHITE_TERRACOTTA.defaultBlockState();
        *///?}
    }

    private static BlockState wool(boolean red) {
        //? if >=26.2 {
        return Blocks.WOOL.pick(red ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.YELLOW).defaultBlockState();
        //?} else {
        /*return (red ? Blocks.RED_WOOL : Blocks.YELLOW_WOOL).defaultBlockState();
        *///?}
    }

    private static BlockState redCarpet() {
        //? if >=26.2 {
        return Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState();
        //?} else {
        /*return Blocks.RED_CARPET.defaultBlockState();
        *///?}
    }

    private static long mix(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        return h * 0xBF58476D1CE4E5B9L;
    }

    private static boolean nearRoad(ServerLevel level, Lot l) {
        for (int x = l.x0() - 4; x < l.x0() + l.w() + 4; x++)
            for (int z = l.z0() - 4; z < l.z0() + l.d() + 4; z++)
                if (RoadColumns.isRoad(level, x, z)) return true;
        return false;
    }

    private static void put(ServerLevel level, int x, int y, int z, BlockState s) {
        BlockPos p = new BlockPos(x, y, z);
        level.setBlock(p, Block.updateFromNeighbourShapes(s, level, p), 3);
    }

    /** Levels the lot to its middle's ground and builds it; returns a house's door (lower half). */
    private static BlockPos build(ServerLevel level, Lot l) {
        int cx = l.x0() + l.w() / 2, cz = l.z0() + l.d() / 2;
        int base = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = l.x0(); x < l.x0() + l.w(); x++)
            for (int z = l.z0(); z < l.z0() + l.d(); z++) {
                for (int y = base + 1; y <= base + 14; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
                for (int y = base - 1; y >= base - 6 && !level.getBlockState(new BlockPos(x, y, z)).isSolid(); y--) level.setBlock(new BlockPos(x, y, z), dirt, 2);
                level.setBlock(new BlockPos(x, base, z), grass, 2);
            }
        int xa = l.x0(), xb = l.x0() + l.w() - 1, za = l.z0(), zb = l.z0() + l.d() - 1;
        long h = mix(xa, za);
        switch (l.type()) {
            case CABIN, CONCRETE, STONE, TERRACOTTA -> {
                BlockState wall = switch (l.type()) {
                    case CABIN -> Blocks.OAK_PLANKS.defaultBlockState();
                    case CONCRETE -> concrete((h & 1) == 0);
                    case STONE -> Blocks.STONE_BRICKS.defaultBlockState();
                    default -> whiteTerracotta();
                };
                BlockState floor = switch (l.type()) {
                    case CABIN -> Blocks.SPRUCE_PLANKS.defaultBlockState();
                    case CONCRETE -> Blocks.SMOOTH_STONE.defaultBlockState();
                    case STONE -> Blocks.POLISHED_ANDESITE.defaultBlockState();
                    default -> whiteTerracotta();
                };
                BlockState roof = l.type() == CABIN ? Blocks.OAK_SLAB.defaultBlockState() : wall;
                // a one-block yard ring around the house
                int ha = xa + 1, hb = xb - 1, ka = za + 1, kb = zb - 1;
                for (int x = ha; x <= hb; x++)
                    for (int z = ka; z <= kb; z++) {
                        put(level, x, base, z, floor);
                        boolean edge = x == ha || x == hb || z == ka || z == kb;
                        for (int y = base + 1; y <= base + 3 && edge; y++) put(level, x, y, z, wall);
                        put(level, x, base + 4, z, roof);
                    }
                // windows: a pane in the middle of each wall
                int mx = (ha + hb) / 2, mz = (ka + kb) / 2;
                for (int[] w : new int[][]{{mx, ka}, {mx, kb}, {ha, mz}, {hb, mz}}) put(level, w[0], base + 2, w[1], Blocks.GLASS_PANE.defaultBlockState());
                // the door: south for the start cabin, else a side by hash
                Direction face = l.start() ? Direction.SOUTH : Direction.from2DDataValue((int) Math.floorMod(h >>> 3, 4));
                int dx = face == Direction.EAST ? hb : face == Direction.WEST ? ha : mx + 1, dz = face == Direction.SOUTH ? kb : face == Direction.NORTH ? ka : mz + 1;
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, face);
                level.setBlock(new BlockPos(dx, base + 1, dz), lower, 2);
                level.setBlock(new BlockPos(dx, base + 2, dz), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
                if (l.type() == STONE) put(level, mx, base + 5, mz, Blocks.LANTERN.defaultBlockState());
                return new BlockPos(dx, base + 1, dz);
            }
            case FIELD -> {
                int gate = xa + 1 + (int) Math.floorMod(h >>> 5, Math.max(1, l.w() - 2));
                for (int x = xa; x <= xb; x++)
                    for (int z = za; z <= zb; z++) {
                        boolean edge = x == xa || x == xb || z == za || z == zb;
                        if (edge) put(level, x, base + 1, z, x == gate && z == zb ? Blocks.OAK_FENCE_GATE.defaultBlockState() : Blocks.OAK_FENCE.defaultBlockState());
                        else if (x == (xa + xb) / 2 && z == (za + zb) / 2) put(level, x, base, z, Blocks.WATER.defaultBlockState());
                        else {
                            level.setBlock(new BlockPos(x, base, z), Blocks.FARMLAND.defaultBlockState(), 2);
                            level.setBlock(new BlockPos(x, base + 1, z), Blocks.WHEAT.defaultBlockState(), 2);
                        }
                    }
            }
            case PLAZA -> {
                for (int x = xa; x <= xb; x++)
                    for (int z = za; z <= zb; z++)
                        put(level, x, base, z, ((x + z) & 3) == 0 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState());
                for (int[] p : new int[][]{{xa + 1, za + 1}, {xb - 1, zb - 1}}) {
                    put(level, p[0], base + 1, p[1], Blocks.OAK_FENCE.defaultBlockState());
                    put(level, p[0], base + 2, p[1], Blocks.LANTERN.defaultBlockState());
                }
            }
            case YARD -> {
                for (int x = xa; x <= xb; x++)
                    for (int z = za; z <= zb; z++) {
                        boolean edge = x == xa || x == xb || z == za || z == zb;
                        if (edge && !(z == za && (x == xa + 2 || x == xa + 3))) put(level, x, base + 1, z, Blocks.IRON_BARS.defaultBlockState());
                        else if (!edge && ((x * 7 + z * 3) & 3) == 0) put(level, x, base + 1, z, (x & 1) == 0 ? Blocks.BARREL.defaultBlockState() : Blocks.CHEST.defaultBlockState());
                    }
                put(level, xb - 1, base + 1, zb - 1, Blocks.CRAFTING_TABLE.defaultBlockState());
            }
            case MARKET -> {
                for (int s = 0; s < 2; s++) {
                    int sx = xa + 1 + s * 4, sz = za + 2;
                    for (int[] p : new int[][]{{sx, sz}, {sx + 2, sz}, {sx, sz + 2}, {sx + 2, sz + 2}})
                        for (int y = base + 1; y <= base + 2; y++) put(level, p[0], y, p[1], Blocks.SPRUCE_FENCE.defaultBlockState());
                    for (int x = sx; x <= sx + 2; x++)
                        for (int z = sz; z <= sz + 2; z++) put(level, x, base + 3, z, wool(s == 0));
                    put(level, sx + 1, base + 1, sz + 1, Blocks.BARREL.defaultBlockState());
                    put(level, sx + 1, base + 1, sz, redCarpet());
                }
            }
            default -> {}
        }
        return null;
    }

}
