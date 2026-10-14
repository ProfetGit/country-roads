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
    static final Surface DESERT_0 = new Surface("desert",
        new BlockState[]{b(Blocks.SMOOTH_SANDSTONE), b(Blocks.SANDSTONE), b(Blocks.SAND)}, new int[]{260, 60},
        new BlockState[]{b(Blocks.SAND), b(Blocks.SANDSTONE), b(Blocks.SMOOTH_SANDSTONE)}, new int[]{240, 160},
        b(Blocks.SMOOTH_SANDSTONE_SLAB), 220);
    static final Surface BADLANDS_0 = new Surface("badlands",
        new BlockState[]{b(Blocks.SMOOTH_RED_SANDSTONE), b(Blocks.RED_SANDSTONE), b(Blocks.COARSE_DIRT)}, new int[]{240, 120},
        new BlockState[]{b(Blocks.RED_SAND), b(Blocks.TERRACOTTA), b(Blocks.COARSE_DIRT)}, new int[]{200, 200},
        b(Blocks.SMOOTH_RED_SANDSTONE_SLAB), 200);
    // dry-country options shown to the user 2026-10-13 (sand-coloured roads were hard to see): a dirt caravan track,
    // a gravel track, mud-brick paving
    static final Surface DESERT_A = new Surface("desert",
        new BlockState[]{b(Blocks.PACKED_MUD), b(Blocks.COARSE_DIRT), b(Blocks.DIRT_PATH)}, new int[]{350, 110},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD), b(Blocks.SAND)}, new int[]{300, 160},
        b(Blocks.MUD_BRICK_SLAB), 90);
    /** User pick 2026-10-13: A (dirt track) with path blocks worked in. */
    static final Surface DESERT_P = new Surface("desert",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.PACKED_MUD), b(Blocks.COARSE_DIRT)}, new int[]{330, 250},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.DIRT_PATH), b(Blocks.PACKED_MUD), b(Blocks.SAND)}, new int[]{260, 200, 140},
        b(Blocks.MUD_BRICK_SLAB), 90);
    static final Surface BADLANDS_P = new Surface("badlands",
        new BlockState[]{b(Blocks.DIRT_PATH), b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD)}, new int[]{330, 230},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.DIRT_PATH), b(Blocks.PACKED_MUD), b(Blocks.RED_SAND)}, new int[]{260, 200, 140},
        b(Blocks.MUD_BRICK_SLAB), 90);
    static final Surface DESERT_B = new Surface("desert",
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD)}, new int[]{250, 120},
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT), b(Blocks.SAND)}, new int[]{300, 160},
        b(Blocks.COBBLESTONE_SLAB), 90);
    static final Surface DESERT_C = new Surface("desert",
        new BlockState[]{b(Blocks.MUD_BRICKS), b(Blocks.PACKED_MUD), b(Blocks.COARSE_DIRT)}, new int[]{300, 80},
        new BlockState[]{b(Blocks.PACKED_MUD), b(Blocks.MUD_BRICKS), b(Blocks.COARSE_DIRT), b(Blocks.SAND)}, new int[]{250, 200, 120},
        b(Blocks.MUD_BRICK_SLAB), 60);
    static final Surface BADLANDS_A = new Surface("badlands",
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD), b(Blocks.DIRT_PATH)}, new int[]{300, 150},
        new BlockState[]{b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD), b(Blocks.RED_SAND)}, new int[]{250, 160},
        b(Blocks.MUD_BRICK_SLAB), 90);
    static final Surface BADLANDS_B = new Surface("badlands",
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT), b(Blocks.ANDESITE)}, new int[]{250, 70},
        new BlockState[]{b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT), b(Blocks.RED_SAND)}, new int[]{300, 160},
        b(Blocks.COBBLESTONE_SLAB), 90);
    static final Surface BADLANDS_C = new Surface("badlands",
        new BlockState[]{b(Blocks.MUD_BRICKS), b(Blocks.PACKED_MUD), b(Blocks.COARSE_DIRT)}, new int[]{300, 80},
        new BlockState[]{b(Blocks.PACKED_MUD), b(Blocks.MUD_BRICKS), b(Blocks.COARSE_DIRT), b(Blocks.RED_SAND)}, new int[]{250, 200, 120},
        b(Blocks.MUD_BRICK_SLAB), 60);
    static final String DRY = System.getProperty("countryroads.dry", "p");
    static final Surface DESERT = switch (DRY) { case "0" -> DESERT_0; case "a" -> DESERT_A; case "b" -> DESERT_B; case "c" -> DESERT_C; default -> DESERT_P; };
    static final Surface BADLANDS = switch (DRY) { case "0" -> BADLANDS_0; case "a" -> BADLANDS_A; case "b" -> BADLANDS_B; case "c" -> BADLANDS_C; default -> BADLANDS_P; };

    /**
     * Wall and post stones in the biome's own rock (user, 2026-10-13: sandstone in deserts, red sandstone in badlands
     * instead of cobblestone). Cobble-family blocks map to the matching sandstone shape; anything else is unchanged.
     */
    static BlockState localStone(BlockState s, Surface pal) {
        boolean desert = pal == DESERT, red = pal == BADLANDS;
        if (!desert && !red || s == null) return s;
        Block b = s.getBlock();
        Block out;
        if (b == Blocks.COBBLESTONE || b == Blocks.ANDESITE) out = red ? Blocks.RED_SANDSTONE : Blocks.SANDSTONE;
        else if (b == Blocks.MOSSY_COBBLESTONE) out = red ? Blocks.CUT_RED_SANDSTONE : Blocks.CUT_SANDSTONE;
        else if (b == Blocks.STONE) out = red ? Blocks.SMOOTH_RED_SANDSTONE : Blocks.SMOOTH_SANDSTONE;
        else if (b == Blocks.COBBLESTONE_SLAB || b == Blocks.ANDESITE_SLAB) out = red ? Blocks.RED_SANDSTONE_SLAB : Blocks.SANDSTONE_SLAB;
        else if (b == Blocks.MOSSY_COBBLESTONE_SLAB) out = red ? Blocks.CUT_RED_SANDSTONE_SLAB : Blocks.CUT_SANDSTONE_SLAB;
        else if (b == Blocks.STONE_SLAB) out = red ? Blocks.SMOOTH_RED_SANDSTONE_SLAB : Blocks.SMOOTH_SANDSTONE_SLAB;
        else if (b == Blocks.COBBLESTONE_WALL || b == Blocks.MOSSY_COBBLESTONE_WALL || b == Blocks.ANDESITE_WALL) out = red ? Blocks.RED_SANDSTONE_WALL : Blocks.SANDSTONE_WALL;
        else if (b == Blocks.COBBLESTONE_STAIRS) out = red ? Blocks.RED_SANDSTONE_STAIRS : Blocks.SANDSTONE_STAIRS;
        else if (b == Blocks.STONE_STAIRS) out = red ? Blocks.SMOOTH_RED_SANDSTONE_STAIRS : Blocks.SMOOTH_SANDSTONE_STAIRS;
        else return s;
        return out.withPropertiesOf(s);
    }
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

    /** House paths are made of path blocks, coarse dirt and rooted dirt only (user 2026-10-14: no mud, gravel or sand); the walked line is always path. */
    private static BlockState flat(BlockState s) {
        return Blocks.DIRT_PATH.defaultBlockState();
    }

    /** A side column of a house path's bed: now and then the natural top block stays, so the path frays a little. */
    public static BlockState houseEdge(Holder<Biome> biome, long h, BlockState natural) {
        Surface pal = surface(biome);
        if (keepable(pal, natural) && ((h >>> 8) & 1023) < 200) return natural;
        // coarse dirt (and, outside dry country, a little rooted dirt) at the edge of the bed, not on the walked line: a full cube beside path blocks reads as a worn verge there
        int e = (int) ((h >>> 40) & 1023);
        if (e < 260) return Blocks.COARSE_DIRT.defaultBlockState();
        if (pal != DESERT && pal != BADLANDS && e < 330) return Blocks.ROOTED_DIRT.defaultBlockState();
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

    /**
     * House path step style: auto (mud brick, fieldstone on rocky ground; user pick 2026-10-13), mix (fieldstone
     * everywhere), mud or cobble; dry country uses its sandstone in every style.
     */
    static final String STEP = System.getProperty("countryroads.step", "auto");
    private static final Block[] COBBLE_SET = {Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_STAIRS}, MOSSY_SET = {Blocks.MOSSY_COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_STAIRS},
        STONE_SET = {Blocks.STONE_SLAB, Blocks.STONE_STAIRS}, ANDESITE_SET = {Blocks.ANDESITE_SLAB, Blocks.ANDESITE_STAIRS},
        MUD_SET = {Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_STAIRS}, SAND_SET = {Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_STAIRS},
        SMOOTH_SAND_SET = {Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE_STAIRS}, RED_SET = {Blocks.RED_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_STAIRS},
        SMOOTH_RED_SET = {Blocks.SMOOTH_RED_SANDSTONE_SLAB, Blocks.SMOOTH_RED_SANDSTONE_STAIRS};

    /**
     * A step of a house path on land: a bottom slab (half step) or a stair, unrotated. Mud brick, softer against the
     * dirt path; on rocky ground (and in tunnels) fieldstone: cobblestone, mossy cobblestone, stone and andesite mixed
     * block by block, weighted per biome (more moss where it is wet, none in snow or dry country). Sandstone in
     * deserts, red sandstone in badlands.
     */
    public static BlockState houseStep(Holder<Biome> biome, long h, boolean slab, boolean rocky) {
        Surface pal = surface(biome);
        int r = (int) ((h >>> 28) & 1023);
        Block[] set;
        // beaches share the desert road palette, but white sandstone steps on a temperate shore stand out: sandstone only
        // in real deserts
        boolean desert = pal == DESERT && (biome.is(ConventionalBiomeTags.IS_DESERT) || biome.is(BiomeTags.IS_BADLANDS) || !biome.is(ConventionalBiomeTags.IS_BEACH));
        if (desert) set = r < 600 ? SAND_SET : SMOOTH_SAND_SET;
        else if (pal == BADLANDS) set = r < 600 ? RED_SET : SMOOTH_RED_SET;
        else if (STEP.equals("mud") || STEP.equals("auto") && !rocky) set = MUD_SET;
        else if (STEP.equals("cobble")) {
            int moss = pal == SNOWY || pal == SAVANNA ? 0 : pal == JUNGLE || pal == SWAMP ? 520 : 260;
            set = r < moss ? MOSSY_SET : COBBLE_SET;
        } else {
            // cobble, mossy, stone, andesite thresholds of 1024
            int[] w = pal == SNOWY || pal == SAVANNA ? new int[]{400, 400, 700}
                : pal == JUNGLE || pal == SWAMP ? new int[]{300, 860, 960}
                : pal == TAIGA ? new int[]{340, 700, 820}
                : pal == STONY ? new int[]{300, 360, 700}
                : new int[]{360, 620, 820};
            set = r < w[0] ? COBBLE_SET : r < w[1] ? MOSSY_SET : r < w[2] ? STONE_SET : ANDESITE_SET;
        }
        return set[slab ? 0 : 1].defaultBlockState();
    }

    /** Floor of a tunnel: path blocks worn between gravel, packed mud and the odd cobble; the sides stonier. */
    public static BlockState tunnelFloor(Holder<Biome> biome, long h, boolean edge) {
        int r = (int) ((h >>> 8) & 1023);
        if (!edge) {
            if (r < 460) return Blocks.DIRT_PATH.defaultBlockState();
            if (r < 640) return Blocks.GRAVEL.defaultBlockState();
            if (r < 780) return Blocks.COARSE_DIRT.defaultBlockState();
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

    /**
     * Bridge details (comma list): trim (darker stripped beams, posts above the rail), stone (cobble footings), trapdoor (on the beams' outer faces, trim wood), lantern
     * (on the end posts). User pick 2026-10-13: all three.
     */
    static final java.util.Set<String> BRIDGE = java.util.Set.of(System.getProperty("countryroads.bridge", "trim,stone,lantern,trapdoor").split(","));

    public static boolean bridgeDetail(String what) {
        return BRIDGE.contains(what);
    }

    /** Bridge beams: the deck's own wood, or with "trim" a darker stripped log (posts stay in the deck's own log). */
    public static Wood bridgeFrame(Holder<Biome> biome) {
        int w = wood(biome);
        if (!bridgeDetail("trim")) return wood(w);
        return wood(w == 0 ? 3 : 0);
    }

    public static BlockState woodStripped(Holder<Biome> biome) {
        return wood(wood(biome)).strippedLog().defaultBlockState();
    }

    public static BlockState woodFence(Holder<Biome> biome) {
        return wood(wood(biome)).fence().defaultBlockState();
    }

    public static BlockState woodSlab(Holder<Biome> biome) {
        return wood(wood(biome)).slab().defaultBlockState();
    }

    /** The stairs of a plank block (bridge undersides), or null. */
    public static Block stairsOf(Block planks) {
        if (planks == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_STAIRS;
        if (planks == Blocks.OAK_PLANKS) return Blocks.OAK_STAIRS;
        if (planks == Blocks.MANGROVE_PLANKS) return Blocks.MANGROVE_STAIRS;
        if (planks == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_STAIRS;
        if (planks == Blocks.JUNGLE_PLANKS) return Blocks.JUNGLE_STAIRS;
        return null;
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
    /** Desert and badlands verges (sparser plants). */
    public static boolean dryVerge(Holder<Biome> biome) {
        Surface pal = surface(biome);
        return pal == DESERT || pal == BADLANDS;
    }

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

    /**
     * Road surfaces that get the odd pebble (a stone button) on top: stony ones and the full-height dirt blocks. Not dirt
     * path: it is a sixteenth lower than a block, so a button on it would float.
     */
    public static boolean pebbly(BlockState surface) {
        return surface.is(Blocks.GRAVEL) || surface.is(Blocks.COBBLESTONE) || surface.is(Blocks.MOSSY_COBBLESTONE) || surface.is(Blocks.ANDESITE)
            || surface.is(Blocks.STONE) || surface.is(Blocks.TUFF) || surface.is(Blocks.COARSE_DIRT) || surface.is(Blocks.PACKED_MUD)
            || surface.is(Blocks.ROOTED_DIRT);
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

    /** Road bridge woods: {main, trim} pairs as the house-path kit uses them (trim always darker). */
    static final String[][] BRIDGE_PAIRS = {{"oak", "spruce"}, {"birch", "spruce"}, {"spruce", "dark_oak"}, {"dark_oak", "spruce"},
        {"jungle", "dark_oak"}, {"acacia", "dark_oak"}, {"mangrove", "dark_oak"}, {"cherry", "dark_oak"}, {"pale_oak", "dark_oak"}};

    /** One wood's bridge blocks. */
    public record BridgeWood(BlockState planks, BlockState log, BlockState stripped, BlockState fence, BlockState slab, BlockState stairs) {}

    private static final BridgeWood[] BRIDGE_WOODS = new BridgeWood[BRIDGE_PAIRS.length * 2];

    public static byte bridgeWoods(Holder<Biome> biome) {
        String[] w = io.github.profetgit.countryroads.path.BridgeKit.woods(biome);
        for (int k = 0; k < BRIDGE_PAIRS.length; k++) if (BRIDGE_PAIRS[k][0].equals(w[0]) && BRIDGE_PAIRS[k][1].equals(w[1])) return (byte) k;
        return 0;
    }

    /** The main (trim false) or trim wood of a pair from bridgeWoods. */
    static BridgeWood bridgeWood(int pair, boolean trim) {
        int k = Math.clamp(pair, 0, BRIDGE_PAIRS.length - 1) * 2 + (trim ? 1 : 0);
        BridgeWood w = BRIDGE_WOODS[k];
        if (w == null) {
            String n = BRIDGE_PAIRS[k / 2][k % 2];
            String log = n + "_log";
            w = new BridgeWood(named(n + "_planks"), named(log), named("stripped_" + log), named(n + "_fence"), named(n + "_slab"), named(n + "_stairs"));
            BRIDGE_WOODS[k] = w;
        }
        return w;
    }

    private static BlockState named(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", id)).defaultBlockState();
    }

    /** Bridge stone roles: dressed (arch rings, piers, parapets), rubble (spandrel walls, abutments), paving (deck), footing (under water). */
    static final int DRESSED = 0, RUBBLE = 1, PAVING = 2, FOOTING = 3;

    /**
     * Weathered bridge stone, one palette for every stone bridge. moss: 0 dry, 1 mossy, 2 thick (well below the moss
     * line, where moss blocks show through). r: 0..1023.
     */
    static BlockState bridgeStone(int role, int moss, int r) {
        if (role == PAVING) {
            if (r < 460) return b(Blocks.STONE_BRICKS);
            if (r < 620) return b(Blocks.CRACKED_STONE_BRICKS);
            if (r < 780) return b(Blocks.COBBLESTONE);
            if (r < 900) return b(Blocks.POLISHED_ANDESITE);
            if (r < 980) return b(Blocks.ANDESITE);
            return b(Blocks.STONE);
        }
        if (role == FOOTING) {
            if (r < 520) return b(Blocks.MOSSY_COBBLESTONE);
            if (r < 800) return b(Blocks.COBBLESTONE);
            if (r < 920) return b(Blocks.ANDESITE);
            return b(Blocks.MOSSY_STONE_BRICKS);
        }
        if (moss == 2) {
            if (r < 240) return b(Blocks.MOSS_BLOCK);
            if (r < 700) return role == DRESSED ? b(Blocks.MOSSY_STONE_BRICKS) : b(Blocks.MOSSY_COBBLESTONE);
            return role == DRESSED ? b(Blocks.MOSSY_COBBLESTONE) : b(Blocks.MOSSY_STONE_BRICKS);
        }
        if (moss == 1) {
            if (role == DRESSED) return r < 800 ? b(Blocks.MOSSY_STONE_BRICKS) : b(Blocks.MOSSY_COBBLESTONE);
            return r < 720 ? b(Blocks.MOSSY_COBBLESTONE) : b(Blocks.MOSSY_STONE_BRICKS);
        }
        if (role == DRESSED) {
            if (r < 700) return b(Blocks.STONE_BRICKS);
            if (r < 880) return b(Blocks.CRACKED_STONE_BRICKS);
            if (r < 960) return b(Blocks.STONE);
            return b(Blocks.POLISHED_ANDESITE);
        }
        if (r < 340) return b(Blocks.COBBLESTONE);
        if (r < 560) return b(Blocks.STONE);
        if (r < 760) return b(Blocks.ANDESITE);
        if (r < 900) return b(Blocks.STONE_BRICKS);
        return b(Blocks.CRACKED_STONE_BRICKS);
    }

    /** Stairs and slabs in the bridge's dressed stone, mossy where the moss reaches. */
    static BlockState bridgeStair(int moss) {
        return b(moss > 0 ? Blocks.MOSSY_STONE_BRICK_STAIRS : Blocks.STONE_BRICK_STAIRS);
    }

    static BlockState bridgeSlab(int moss, int r) {
        if (moss > 0) return b(Blocks.MOSSY_STONE_BRICK_SLAB);
        return b(r < 900 ? Blocks.STONE_BRICK_SLAB : Blocks.ANDESITE_SLAB);
    }
}
