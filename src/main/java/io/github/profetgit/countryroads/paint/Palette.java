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

    public record Wood(Block planks, Block log, Block fence, Block slab, Block sign) {}

    static final Wood[] WOODS = {
        new Wood(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_SIGN),
        new Wood(Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_FENCE, Blocks.OAK_SLAB, Blocks.OAK_SIGN),
        new Wood(Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_LOG, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_SLAB, Blocks.MANGROVE_SIGN),
        new Wood(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_SIGN),
        new Wood(Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_FENCE, Blocks.JUNGLE_SLAB, Blocks.JUNGLE_SIGN),
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
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL), b(Blocks.PACKED_MUD)}, new int[]{90, 40, 110},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL), b(Blocks.PACKED_MUD)}, new int[]{330, 60, 90},
        b(Blocks.MUD_BRICK_SLAB), 120);
    static final Surface TAIGA = new Surface("taiga",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL), b(Blocks.PODZOL), b(Blocks.PACKED_MUD)}, new int[]{110, 90, 20, 90},
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL)}, new int[]{260, 200, 80},
        b(Blocks.SPRUCE_SLAB), 140);
    static final Surface SNOWY = new Surface("snowy",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT)}, new int[]{260, 90},
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE), b(Blocks.DIRT_PATH)}, new int[]{330, 90, 200},
        b(Blocks.SPRUCE_SLAB), 160);
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
        if (b.is(ConventionalBiomeTags.IS_DESERT) || b.is(ConventionalBiomeTags.IS_SANDY) || b.is(ConventionalBiomeTags.IS_BEACH)) return DESERT;
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
            if (r < pal.keepNatural() && onGround && PaintRules.terrain(natural) && !natural.is(Blocks.DIRT)) return natural;
            return pal.pick(pal.edge(), pal.edgeW(), (int) ((h >>> 24) & 1023));
        }
        return pal.pick(pal.centre(), pal.centreW(), r);
    }

    static BlockState masonry(long h) {
        int r = (int) ((h >>> 12) & 15);
        if (r < 3) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (r < 5) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    static BlockState rubble(long h) {
        return ((h >>> 16) & 3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
    }
}
