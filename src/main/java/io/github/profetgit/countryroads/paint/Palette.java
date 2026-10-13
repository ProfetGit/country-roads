package io.github.profetgit.countryroads.paint;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;

/** Road materials. Biome surface palettes come with the look pass; wood styles are chosen per 16 samples at plan time. */
public final class Palette {
    private Palette() {}

    public record Wood(Block planks, Block log, Block fence, Block slab, Block sign, Block wallSign, Block strippedLog, Block trapdoor, Block gate, Block hangingSign) {}

    static final Wood[] WOODS = {
        new Wood(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_SIGN, Blocks.SPRUCE_WALL_SIGN,
            Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_FENCE_GATE, Blocks.SPRUCE_HANGING_SIGN),
        new Wood(Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_FENCE, Blocks.OAK_SLAB, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN,
            Blocks.STRIPPED_OAK_LOG, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.OAK_HANGING_SIGN),
        new Wood(Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_LOG, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_SLAB, Blocks.MANGROVE_SIGN, Blocks.MANGROVE_WALL_SIGN,
            Blocks.STRIPPED_MANGROVE_LOG, Blocks.MANGROVE_TRAPDOOR, Blocks.MANGROVE_FENCE_GATE, Blocks.MANGROVE_HANGING_SIGN),
        new Wood(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_SIGN, Blocks.DARK_OAK_WALL_SIGN,
            Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DARK_OAK_TRAPDOOR, Blocks.DARK_OAK_FENCE_GATE, Blocks.DARK_OAK_HANGING_SIGN),
        new Wood(Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_FENCE, Blocks.JUNGLE_SLAB, Blocks.JUNGLE_SIGN, Blocks.JUNGLE_WALL_SIGN,
            Blocks.STRIPPED_JUNGLE_LOG, Blocks.JUNGLE_TRAPDOOR, Blocks.JUNGLE_FENCE_GATE, Blocks.JUNGLE_HANGING_SIGN),
    };

    public static byte wood(Holder<Biome> b) {
        if (b.is(Biomes.MANGROVE_SWAMP)) return 2;
        if (b.is(Biomes.DARK_FOREST) || b.is(Biomes.PALE_GARDEN)) return 3;
        if (b.is(BiomeTags.IS_JUNGLE)) return 4;
        if (b.is(BiomeTags.IS_TAIGA) || b.value().getBaseTemperature() < 0.4f) return 0;
        return 1;
    }

    static Wood wood(int style) {
        return WOODS[Math.clamp(style, 0, WOODS.length - 1)];
    }

    /**
     * A biome's road look: centre blocks and edge blocks as weighted lists (weights out of 1024, the rest is the
     * first entry), the half-step slab, and the chance (of 1024) that an edge column keeps its natural top block.
     */
    public record Surface(String name, BlockState[] centre, int[] centreW, BlockState[] edge, int[] edgeW, BlockState step, int keepNatural) {
        BlockState pick(BlockState[] blocks, int[] weights, int r) {
            int acc = 0;
            for (int k = 1; k < blocks.length; k++) {
                acc += weights[k - 1];
                if (r < acc) return blocks[k];
            }
            return blocks[0];
        }
    }

    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    static final Surface TEMPERATE = new Surface("temperate",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.ROOTED_DIRT), b(Blocks.PACKED_MUD)}, new int[]{90, 60, 110},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.MOSS_BLOCK), b(Blocks.PACKED_MUD)}, new int[]{240, 160, 80},
        b(Blocks.MUD_BRICK_SLAB), 120);
    static final Surface TAIGA = new Surface("taiga",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.ROOTED_DIRT), b(Blocks.PODZOL), b(Blocks.PACKED_MUD)}, new int[]{110, 50, 20, 90},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.MOSS_BLOCK)}, new int[]{240, 180, 140},
        b(Blocks.MUD_BRICK_SLAB), 140);
    static final Surface SNOWY = new Surface("snowy",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT)}, new int[]{140, 60},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.GRAVEL), b(Blocks.SNOW_BLOCK)}, new int[]{300, 110},
        b(Blocks.COBBLESTONE_SLAB), 200);
    static final Surface DESERT = new Surface("desert",
        new BlockState[]{b(Blocks.SMOOTH_SANDSTONE), b(Blocks.SANDSTONE), b(Blocks.SAND)}, new int[]{260, 60},
        new BlockState[]{b(Blocks.SAND), b(Blocks.SANDSTONE), b(Blocks.SMOOTH_SANDSTONE)}, new int[]{240, 160},
        b(Blocks.SMOOTH_SANDSTONE_SLAB), 220);
    static final Surface BADLANDS = new Surface("badlands",
        new BlockState[]{b(Blocks.SMOOTH_RED_SANDSTONE), b(Blocks.RED_SANDSTONE), b(Blocks.COARSE_DIRT)}, new int[]{240, 120},
        new BlockState[]{b(Blocks.RED_SAND), b(Blocks.TERRACOTTA), b(Blocks.COARSE_DIRT)}, new int[]{200, 200},
        b(Blocks.SMOOTH_RED_SANDSTONE_SLAB), 200);
    static final Surface SAVANNA = new Surface("savanna",
        new BlockState[]{b(Blocks.PACKED_MUD), b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT)}, new int[]{280, 170},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD), b(Blocks.DIRT_PATH)}, new int[]{260, 200},
        b(Blocks.MUD_BRICK_SLAB), 150);
    static final Surface SWAMP = new Surface("swamp",
        new BlockState[]{b(Blocks.PACKED_MUD), b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT)}, new int[]{300, 90},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD), b(Blocks.DIRT_PATH)}, new int[]{300, 260},
        b(Blocks.MUD_BRICK_SLAB), 160);
    static final Surface JUNGLE = new Surface("jungle",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.ROOTED_DIRT), b(Blocks.MOSS_BLOCK), b(Blocks.PACKED_MUD)}, new int[]{140, 90, 40, 90},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.MOSS_BLOCK), b(Blocks.ROOTED_DIRT), b(Blocks.COARSE_DIRT)}, new int[]{220, 160, 120},
        b(Blocks.MOSSY_COBBLESTONE_SLAB), 160);
    static final Surface STONY = new Surface("stony",
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.DIRT_PATH), b(Blocks.COBBLESTONE), b(Blocks.ANDESITE)}, new int[]{320, 90, 60},
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.COBBLESTONE), b(Blocks.STONE), b(Blocks.ANDESITE)}, new int[]{200, 200, 120},
        b(Blocks.COBBLESTONE_SLAB), 220);

    /** Palette from biome tags (vanilla, then conventional c: tags), else from climate. */
    public static Surface surface(Holder<Biome> b) {
        if (b.is(ConventionalBiomeTags.IS_BADLANDS) || b.is(BiomeTags.IS_BADLANDS)) return BADLANDS;
        boolean cold = b.is(ConventionalBiomeTags.IS_SNOWY) || b.is(ConventionalBiomeTags.IS_ICY) || b.value().getBaseTemperature() < 0.15f;
        if (!cold && (b.is(ConventionalBiomeTags.IS_DESERT) || b.is(ConventionalBiomeTags.IS_SANDY) || b.is(ConventionalBiomeTags.IS_BEACH))) return DESERT;
        if (b.is(ConventionalBiomeTags.IS_SWAMP) || b.is(Biomes.MANGROVE_SWAMP)) return SWAMP;
        if (b.is(ConventionalBiomeTags.IS_SAVANNA) || b.is(BiomeTags.IS_SAVANNA)) return SAVANNA;
        if (b.is(ConventionalBiomeTags.IS_JUNGLE) || b.is(BiomeTags.IS_JUNGLE)) return JUNGLE;
        if (b.is(ConventionalBiomeTags.IS_SNOWY) || b.is(ConventionalBiomeTags.IS_ICY) || b.value().getBaseTemperature() < 0.15f) return SNOWY;
        if (b.is(ConventionalBiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_TAIGA)) return TAIGA;
        if (b.is(ConventionalBiomeTags.IS_MOUNTAIN_PEAK) || b.is(ConventionalBiomeTags.IS_STONY_SHORES) || b.is(ConventionalBiomeTags.IS_WINDSWEPT)) return STONY;
        float t = b.value().getBaseTemperature();
        if (!b.value().hasPrecipitation() && t > 1.0f) return t > 1.9f ? DESERT : SAVANNA;
        if (t < 0.3f) return TAIGA;
        return TEMPERATE;
    }

    /** in = how far inside the road edge the column is (0 at the edge). */
    static BlockState surface(Surface pal, float in, long h, BlockState natural, boolean onGround) {
        int r = (int) ((h >>> 8) & 1023);
        if (in < 0.6f) {
            if (r < pal.keepNatural() && onGround && keepable(pal, natural)) return natural;
            return pal.pick(pal.edge(), pal.edgeW(), (int) ((h >>> 24) & 1023));
        }
        return pal.pick(pal.centre(), pal.centreW(), r);
    }

    /** House path surface: the biome's road look (edge mix for the outer column). */
    public static BlockState housePath(Holder<Biome> biome, float in, long h, BlockState natural) {
        return flat(surface(surface(biome), in, h, natural, true));
    }

    /** House paths never put full cubes of coarse dirt, moss, podzol or rooted dirt among the path blocks: they read as stray blocks on the path. */
    private static BlockState flat(BlockState s) {
        if (s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.PODZOL)) return Blocks.DIRT_PATH.defaultBlockState();
        return s;
    }

    /** A side column of a house path's bed: now and then the natural top block stays, so the path frays a little. */
    public static BlockState houseEdge(Holder<Biome> biome, long h, BlockState natural) {
        Surface pal = surface(biome);
        if (keepable(pal, natural) && ((h >>> 8) & 1023) < 200) return natural;
        // coarse dirt at the edge of the bed (not on the walked line: a full cube beside path blocks reads as a worn verge there)
        if (pal != SNOWY && pal != DESERT && pal != BADLANDS && ((h >>> 40) & 1023) < 260) return Blocks.COARSE_DIRT.defaultBlockState();
        return flat(pal.pick(pal.edge(), pal.edgeW(), (int) ((h >>> 24) & 1023)));
    }

    /** A straggler beside the worn band: a lone path block on grass, so the trail frays instead of ending on a line. */
    public static BlockState houseFringe(BlockState natural, long h) {
        if (natural.is(Blocks.GRASS_BLOCK) || natural.is(Blocks.DIRT) || natural.is(Blocks.PODZOL) || natural.is(Blocks.COARSE_DIRT))
            return ((h >>> 36) & 3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState();
        return natural;
    }

    public static BlockState houseStair(Holder<Biome> biome) {
        Block slab = surface(biome).step().getBlock();
        if (slab == Blocks.MUD_BRICK_SLAB) return Blocks.MUD_BRICK_STAIRS.defaultBlockState();
        if (slab == Blocks.SMOOTH_SANDSTONE_SLAB) return Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState();
        if (slab == Blocks.SMOOTH_RED_SANDSTONE_SLAB) return Blocks.SMOOTH_RED_SANDSTONE_STAIRS.defaultBlockState();
        if (slab == Blocks.MOSSY_COBBLESTONE_SLAB) return Blocks.MOSSY_COBBLESTONE_STAIRS.defaultBlockState();
        return Blocks.COBBLESTONE_STAIRS.defaultBlockState();
    }

    /** Floor of a tunnel: path blocks worn between gravel, packed mud and the odd cobble; the sides stonier. */
    public static BlockState tunnelFloor(Holder<Biome> biome, long h, boolean edge) {
        int r = (int) ((h >>> 8) & 1023);
        if (!edge) {
            if (r < 460) return Blocks.DIRT_PATH.defaultBlockState();
            if (r < 640) return Blocks.GRAVEL.defaultBlockState();
            if (r < 780) return Blocks.PACKED_MUD.defaultBlockState();
            if (r < 900) return Blocks.COBBLESTONE.defaultBlockState();
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (r < 320) return Blocks.COBBLESTONE.defaultBlockState();
        if (r < 620) return Blocks.GRAVEL.defaultBlockState();
        if (r < 780) return Blocks.ANDESITE.defaultBlockState();
        if (r < 900) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        return Blocks.DIRT_PATH.defaultBlockState();
    }

    /** Portal masonry: cobblestone, moss and a little worked stone, counted in the bill as path material. */
    public static BlockState portalStone(long h) {
        int r = (int) ((h >>> 12) & 15);
        if (r < 8) return Blocks.COBBLESTONE.defaultBlockState();
        if (r < 11) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (r < 14) return Blocks.STONE_BRICKS.defaultBlockState();
        return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    }

    public static BlockState woodLog(Holder<Biome> biome) {
        return wood(wood(biome)).log().defaultBlockState();
    }

    public static BlockState woodPlanks(Holder<Biome> biome) {
        return wood(wood(biome)).planks().defaultBlockState();
    }

    public static BlockState deckStair(Holder<Biome> biome) {
        Block planks = wood(wood(biome)).planks();
        if (planks == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_STAIRS.defaultBlockState();
        if (planks == Blocks.MANGROVE_PLANKS) return Blocks.MANGROVE_STAIRS.defaultBlockState();
        if (planks == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_STAIRS.defaultBlockState();
        if (planks == Blocks.JUNGLE_PLANKS) return Blocks.JUNGLE_STAIRS.defaultBlockState();
        return Blocks.OAK_STAIRS.defaultBlockState();
    }

    public static BlockState deck(Holder<Biome> biome) {
        return wood(wood(biome)).planks().defaultBlockState();
    }

    /**
     * A plant for the verge of a road or path in this biome, or null (r: 0..1023, decides which). Mostly short grass,
     * then tall grass, the biome's own flowers and the odd bush; dry plants on sand and terracotta, none in snow. For
     * tall grass this is the lower half; the caller adds the upper one (see upperHalf).
     */
    public static BlockState vergePlant(Holder<Biome> biome, int r) {
        Surface pal = surface(biome);
        if (pal == SNOWY) return null;
        if (pal == DESERT || pal == BADLANDS) return b(r < 560 ? Blocks.SHORT_DRY_GRASS : Blocks.DEAD_BUSH);
        Block[] flowers;
        int grass, tall, flower;
        if (pal == TAIGA) {
            flowers = new Block[]{Blocks.FERN, Blocks.FERN, Blocks.POPPY, Blocks.DANDELION, Blocks.LILY_OF_THE_VALLEY};
            grass = 420; tall = 520; flower = 860;
        } else if (pal == SWAMP) {
            flowers = new Block[]{Blocks.BLUE_ORCHID, Blocks.FERN, Blocks.BLUE_ORCHID};
            grass = 480; tall = 560; flower = 820;
        } else if (pal == JUNGLE) {
            flowers = new Block[]{Blocks.FERN, Blocks.FERN, Blocks.POPPY};
            grass = 400; tall = 520; flower = 760;
        } else if (pal == SAVANNA) {
            flowers = new Block[]{Blocks.DANDELION, Blocks.POPPY};
            grass = 460; tall = 760; flower = 860;
        } else if (pal == STONY) {
            flowers = new Block[]{Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET};
            grass = 640; tall = 760; flower = 900;
        } else {
            flowers = new Block[]{Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.WILDFLOWERS};
            grass = 480; tall = 640; flower = 900;
        }
        if (r < grass) return b(Blocks.SHORT_GRASS);
        if (r < tall) return b(Blocks.TALL_GRASS);
        if (r < flower) return b(flowers[(r * 7 + 3) % flowers.length]);
        return b(r % 9 == 0 ? Blocks.FIREFLY_BUSH : Blocks.BUSH);
    }

    /** The upper half of a two-block plant, or null for a one-block one. */
    public static BlockState upperHalf(BlockState plant) {
        if (!(plant.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock)) return null;
        return plant.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
    }

    /** Whether this plant can stand on that ground (green plants on soil, dry ones on sand and terracotta). */
    public static boolean plantable(BlockState plant, BlockState ground) {
        if (plant.is(Blocks.DEAD_BUSH) || plant.is(Blocks.SHORT_DRY_GRASS))
            return ground.is(net.minecraft.tags.BlockTags.SAND) || ground.is(net.minecraft.tags.BlockTags.TERRACOTTA) || ground.is(Blocks.COARSE_DIRT);
        return ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT)
            || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.ROOTED_DIRT) || ground.is(Blocks.MYCELIUM);
    }

    /** Stony road surfaces that get the odd pebble (a stone button) on top. */
    public static boolean pebbly(BlockState surface) {
        return surface.is(Blocks.GRAVEL) || surface.is(Blocks.COBBLESTONE) || surface.is(Blocks.MOSSY_COBBLESTONE) || surface.is(Blocks.ANDESITE)
            || surface.is(Blocks.STONE) || surface.is(Blocks.TUFF);
    }

    /** A pebble lying on the road: a stone button, turned by r. */
    public static BlockState pebble(int r) {
        return Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
            .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.from2DDataValue(r & 3));
    }

    /** Which natural top blocks may stay as patches on the road edge (never bare grass in snow, never stray sand). */
    static boolean keepable(Surface pal, BlockState natural) {
        if (pal == SNOWY) return false;
        if (pal == DESERT || pal == BADLANDS) return natural.is(Blocks.SAND) || natural.is(Blocks.RED_SAND);
        return natural.is(Blocks.GRASS_BLOCK) || natural.is(Blocks.PODZOL) || natural.is(Blocks.MYCELIUM);
    }

    static BlockState masonry(long h) {
        int r = (int) ((h >>> 12) & 15);
        if (r < 3) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (r < 5) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    /** Dry-stone wall body block: mostly cobblestone, some stone, andesite and mossy cobble. */
    static BlockState wallBody(long h) {
        int r = (int) ((h >>> 9) & 15);
        if (r < 6) return Blocks.COBBLESTONE.defaultBlockState();
        if (r < 9) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (r < 12) return Blocks.STONE.defaultBlockState();
        return Blocks.ANDESITE.defaultBlockState();
    }

    /** Dry-stone wall cap: slabs, walls and stairs in the same stones, so the top line is uneven. */
    static BlockState wallCap(long h) {
        return switch ((int) ((h >>> 14) & 15)) {
            case 0, 1, 2 -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
            case 3, 4 -> Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState();
            case 5, 6 -> Blocks.STONE_SLAB.defaultBlockState();
            case 7 -> Blocks.ANDESITE_SLAB.defaultBlockState();
            case 8, 9 -> Blocks.COBBLESTONE_WALL.defaultBlockState();
            case 10 -> Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
            case 11 -> Blocks.ANDESITE_WALL.defaultBlockState();
            case 12 -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
            case 13 -> Blocks.STONE_STAIRS.defaultBlockState();
            default -> null;
        };
    }

    static BlockState rubble(long h) {
        return ((h >>> 16) & 3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
    }
}
