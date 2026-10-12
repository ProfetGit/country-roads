package io.github.profetgit.countryroads.paint;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Block classification shared by the painter and the checks. */
public final class PaintRules {
    private PaintRules() {}

    public static boolean solid(BlockState s) {
        return !s.isAir() && s.isSolid();
    }

    public static boolean connects(BlockState s) {
        return s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.STAIRS) || s.is(Blocks.LANTERN);
    }

    /** Ground itself: solid, not a fluid, not a tree. */
    public static boolean terrain(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.isSolid() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
    }

    /** The block a bank or embankment gets on top: the column's natural surface, grass for exposed dirt. */
    static BlockState surfaceOf(BlockState natural) {
        if (natural.is(Blocks.DIRT) || natural.is(Blocks.DIRT_PATH) || natural.is(Blocks.COARSE_DIRT) || natural.is(Blocks.ROOTED_DIRT))
            return Blocks.GRASS_BLOCK.defaultBlockState();
        if (natural.is(Blocks.STONE) || natural.is(Blocks.DEEPSLATE) || natural.is(Blocks.GRAVEL) || natural.is(Blocks.SAND)
            || natural.is(Blocks.RED_SAND) || natural.is(Blocks.SNOW_BLOCK) || natural.is(Blocks.GRASS_BLOCK) || natural.is(Blocks.PODZOL)
            || natural.is(Blocks.MYCELIUM) || natural.is(Blocks.MUD) || natural.is(Blocks.TERRACOTTA) || natural.is(Blocks.MOSS_BLOCK))
            return natural;
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }
}
