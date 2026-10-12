package io.github.profetgit.countryroads.paint;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Road materials. M2: one temperate palette; biome palettes come with the look pass. */
public final class Palette {
    private Palette() {}

    /** in = how far inside the road edge the column is (0 at the edge). */
    static BlockState surface(float in, long h, BlockState natural, boolean onGround) {
        int r = (int) ((h >>> 8) & 1023);
        if (in < 0.6f) {
            if (r < 220 && onGround && natural.is(Blocks.GRASS_BLOCK)) return natural;
            if (r < 520) return Blocks.COARSE_DIRT.defaultBlockState();
        } else if (r < 90) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        } else if (r < 130) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.DIRT_PATH.defaultBlockState();
    }

    static BlockState step(long h) {
        return ((h >>> 20) & 3) == 0 ? Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState() : Blocks.COBBLESTONE_SLAB.defaultBlockState();
    }
}
