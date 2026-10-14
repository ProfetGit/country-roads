package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.CountryRoads;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The user's bridge (2026-10-14, "Medieval Bridge.litematic", built in creative and saved with Build Buddy) as a kit:
 * an END ramp, SPAN units of a post and its deck, a closing post, and the END again mirrored. The file runs along +z
 * with two spans: z 0..4 end, 5..10 and 11..16 spans (posts at 5 and 11), 17 the closing post, 18..22 the end. Any
 * number of spans k gives a bridge of 2*END + 1 + k*SPAN cells (11, 17, 23 ... 65). Width 7: the walked deck is the
 * middle three columns, posts and beams the next ones out, trim on the outside. y 0 is the level of the ramp's first
 * slab; the deck stands 2.5 blocks above it.
 */
public final class BridgeKit {
    /** At least one span (user, 2026-10-14: the bare ramp-post-ramp bridge looked wrong): 17..65 cells. */
    public static final int END = 5, SPAN = 6, WIDTH = 7, MID = 3, MIN_SPANS = 1, MAX_SPANS = 9;

    /** A bridge to lay: z0 is the centre column of its first cell, dir the direction it runs (index into HousePaths.DIRS). */
    public record Bridge(int x, int z, int dir, int spans, int y0) {
        public int length() {
            return BridgeKit.length(spans);
        }
    }

    private static final BlockState[][][] CELLS;
    private static final int SX, SY, SZ;

    static {
        BlockState[][][] cells = null;
        int sx = 0, sy = 0, sz = 0;
        try (InputStream in = BridgeKit.class.getResourceAsStream("/data/countryroads/bridges/medieval_bridge.litematic")) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            CompoundTag r = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
            CompoundTag size = r.getCompoundOrEmpty("Size");
            sx = Math.abs(size.getIntOr("x", 0));
            sy = Math.abs(size.getIntOr("y", 0));
            sz = Math.abs(size.getIntOr("z", 0));
            ListTag pal = r.getListOrEmpty("BlockStatePalette");
            BlockState[] palette = new BlockState[pal.size()];
            for (int i = 0; i < palette.length; i++) palette[i] = state(pal.getCompoundOrEmpty(i));
            long[] packed = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            cells = new BlockState[sx][sy][sz];
            for (int i = 0; i < sx * sy * sz; i++) {
                long bit = (long) i * bits;
                int word = (int) (bit >>> 6), off = (int) (bit & 63);
                long v = packed[word] >>> off;
                if (off + bits > 64) v |= packed[word + 1] << (64 - off);
                int idx = (int) (v & ((1L << bits) - 1));
                int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
                cells[x][y][z] = idx < palette.length ? palette[idx] : Blocks.AIR.defaultBlockState();
            }
        } catch (Exception e) {
            CountryRoads.LOG.error("Country Roads: the bridge kit could not be read", e);
        }
        CELLS = cells;
        SX = sx;
        SY = sy;
        SZ = sz;
    }

    private BridgeKit() {}

    /** Whether the kit loaded and has the expected shape. */
    public static boolean available() {
        return CELLS != null && SX == WIDTH && SZ == 2 * END + 1 + 2 * SPAN;
    }

    public static int length(int spans) {
        return 2 * END + 1 + spans * SPAN;
    }

    public static int height() {
        return SY;
    }

    /** The file's z for cell z (0..length-1) of a bridge with k spans; lanterns sit on every other post, symmetric. */
    static int sourceZ(int k, int z) {
        int len = length(k);
        if (z < END) return z;
        if (z >= len - END) return SZ - (len - z);
        int in = z - END, unit = in / SPAN, at = in % SPAN;
        if (unit == k) return SZ - END - 1;
        boolean lantern = Math.min(unit, k - unit) % 2 == 1;
        return END + (lantern ? SPAN : 0) + at;
    }

    /** The kit block at local (x across 0..6, y up, z along) of a bridge with k spans, as the file has it (running +z). */
    static BlockState at(int k, int x, int y, int z) {
        return CELLS[x][y][sourceZ(k, z)];
    }

    /** Standing height above y0 on the walked line, in half blocks (0 = on the ground at y0 - 1, 5 = the deck). */
    static int halves(int k, int z) {
        for (int y = SY - 1; y >= 0; y--) {
            BlockState s = at(k, MID, y, z);
            if (s.isAir()) continue;
            boolean bottomSlab = s.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && s.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
            return 2 * y + (bottomSlab ? 1 : 2);
        }
        return 0;
    }

    /** The rotation that turns the file (running south, +z) to run toward dir. */
    static Rotation rotation(int dir) {
        return switch (dir) {
            case 2 -> Rotation.NONE;
            case 3 -> Rotation.CLOCKWISE_90;
            case 0 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** For dev checks outside the package. */
    public static BlockPos worldPos(Bridge b, int x, int y, int z) {
        return world(b, x, y, z);
    }

    /** World position of local (x across, y, z along) for a bridge. */
    static BlockPos world(Bridge b, int x, int y, int z) {
        int lx = x - MID, lz = z;
        int wx, wz;
        switch (b.dir()) {
            case 2 -> { wx = lx; wz = lz; }
            case 3 -> { wx = -lz; wz = lx; }
            case 0 -> { wx = -lx; wz = -lz; }
            default -> { wx = lz; wz = -lx; }
        }
        return new BlockPos(b.x() + wx, b.y0() + y, b.z() + wz);
    }

    /** {planks, stone, lanterns} a bridge costs: every kit block as the items it is made of, posts down to the bed about three logs more. */
    static int[] bill(int k) {
        int halfPlanks = 0, stone = 0, lanterns = 0, posts = 0;
        for (int z = 0; z < length(k); z++)
            for (int x = 0; x < SX; x++)
                for (int y = 0; y < SY; y++) {
                    BlockState s = at(k, x, y, z);
                    if (s.isAir()) continue;
                    if (y == 0 && isPost(s)) posts++;
                    if (s.is(Blocks.LANTERN)) lanterns++;
                    else if (s.is(BlockTags.WALLS)) stone++;
                    else if (s.is(BlockTags.LOGS)) halfPlanks += 8;
                    else if (s.is(BlockTags.WOODEN_SLABS)) halfPlanks += 1;
                    else if (s.is(BlockTags.WOODEN_STAIRS)) halfPlanks += 3;
                    else if (s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.WOODEN_SHELVES)) halfPlanks += 6;
                    else if (s.is(BlockTags.FENCE_GATES)) halfPlanks += 8;
                    else if (s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.WOODEN_PRESSURE_PLATES)) halfPlanks += 4;
                    else if (s.is(BlockTags.PLANKS)) halfPlanks += 2;
                    else stone++;
                }
        return new int[]{(halfPlanks + 1) / 2 + posts * 12, stone, lanterns};
    }

    /**
     * The biome's wood pair {main, trim}: main takes the file's oak (deck, posts, beams), trim its spruce (stripped
     * beams, trapdoors, stairs, shelves, gates). User, 2026-10-14: wood per biome, the trim darker than the main wood.
     */
    public static String[] woods(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b) {
        String forced = System.getProperty("countryroads.kitWoods");
        if (forced != null && forced.contains(",")) return forced.split(",", 2);
        if (b.is(net.minecraft.world.level.biome.Biomes.MANGROVE_SWAMP)) return new String[]{"mangrove", "dark_oak"};
        if (b.is(net.minecraft.world.level.biome.Biomes.CHERRY_GROVE)) return new String[]{"cherry", "dark_oak"};
        if (b.is(net.minecraft.world.level.biome.Biomes.PALE_GARDEN)) return new String[]{"pale_oak", "dark_oak"};
        if (b.is(net.minecraft.world.level.biome.Biomes.DARK_FOREST)) return new String[]{"dark_oak", "spruce"};
        if (b.is(BiomeTags.IS_JUNGLE)) return new String[]{"jungle", "dark_oak"};
        if (b.is(BiomeTags.IS_SAVANNA)) return new String[]{"acacia", "dark_oak"};
        if (b.is(net.minecraft.world.level.biome.Biomes.BIRCH_FOREST) || b.is(net.minecraft.world.level.biome.Biomes.OLD_GROWTH_BIRCH_FOREST))
            return new String[]{"birch", "spruce"};
        if (b.is(BiomeTags.IS_TAIGA) || b.value().getBaseTemperature() < 0.15f) return new String[]{"spruce", "dark_oak"};
        return new String[]{"oak", "spruce"};
    }

    private static final java.util.Map<String, BlockState> SWAPPED = new java.util.concurrent.ConcurrentHashMap<>();

    /** A kit block in the bridge's woods: oak -> main, spruce -> trim (same shape and properties); anything else as is. */
    static BlockState inWoods(BlockState s, String[] woods) {
        if (woods[0].equals("oak") && woods[1].equals("spruce")) return s;
        Identifier id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        String path = id.getPath(), to = null;
        String[][] map = {{"oak", woods[0]}, {"spruce", woods[1]}};
        for (String[] m : map) {
            if (path.startsWith(m[0] + "_")) to = m[1] + path.substring(m[0].length());
            else if (path.startsWith("stripped_" + m[0] + "_")) to = "stripped_" + m[1] + path.substring(("stripped_" + m[0]).length());
            if (to != null) break;
        }
        if (to == null) return s;
        String key = s + "|" + to;
        String target = to;
        return SWAPPED.computeIfAbsent(key, k -> {
            Identifier nid = Identifier.fromNamespaceAndPath(id.getNamespace(), target);
            if (!BuiltInRegistries.BLOCK.containsKey(nid)) return s;
            return BuiltInRegistries.BLOCK.getValue(nid).withPropertiesOf(s);
        });
    }

    /** Dev: every kit block in every wood pair; a block that should change but has no counterpart is listed. */
    public static String woodCheck() {
        String[][] pairs = {{"oak", "spruce"}, {"birch", "spruce"}, {"spruce", "dark_oak"}, {"dark_oak", "spruce"}, {"jungle", "dark_oak"},
            {"acacia", "dark_oak"}, {"mangrove", "dark_oak"}, {"cherry", "dark_oak"}, {"pale_oak", "dark_oak"}};
        StringBuilder sb = new StringBuilder("CR-KITWOODS");
        int missing = 0;
        java.util.Set<BlockState> seen = new java.util.HashSet<>();
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) seen.add(CELLS[x][y][z]);
        for (String[] p : pairs)
            for (BlockState s : seen) {
                String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                boolean wood = path.startsWith("oak_") || path.startsWith("spruce_") || path.startsWith("stripped_oak_") || path.startsWith("stripped_spruce_");
                boolean shouldChange = wood && !(p[0].equals("oak") && p[1].equals("spruce"))
                    && !(path.contains("oak") && p[0].equals("oak")) && !(path.contains("spruce") && p[1].equals("spruce"));
                if (shouldChange && inWoods(s, p) == s) {
                    missing++;
                    sb.append(' ').append(p[0]).append('/').append(p[1]).append(':').append(path);
                }
            }
        return (missing == 0 ? "CR-KITWOODS PASS states=" + seen.size() : sb.toString() + " FAIL missing=" + missing);
    }

    static boolean isPost(BlockState s) {
        return s.is(BlockTags.LOGS) && s.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
            && s.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == net.minecraft.core.Direction.Axis.Y;
    }

    private static BlockState state(CompoundTag tag) {
        String name = tag.getString("id").orElseGet(() -> tag.getStringOr("Name", "minecraft:air"));
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.AIR.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        Optional<CompoundTag> props = tag.getCompound("properties").or(() -> tag.getCompound("Properties"));
        if (props.isPresent()) {
            StateDefinition<Block, BlockState> def = block.getStateDefinition();
            for (String key : props.get().keySet()) {
                Property<?> p = def.getProperty(key);
                if (p != null) state = with(state, p, props.get().getStringOr(key, ""));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String v) {
        return p.getValue(v).map(val -> state.setValue(p, val)).orElse(state);
    }
}
