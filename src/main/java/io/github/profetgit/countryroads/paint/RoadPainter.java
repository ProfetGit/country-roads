package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.gen.GenContext;
import io.github.profetgit.countryroads.plan.ChunkMask;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Lays the road into one chunk after its features: every block is a function of the plan (surface Y) and of this
 * column's own blocks, so neighbouring chunks always agree at the border.
 */
public final class RoadPainter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private RoadPainter() {}

    public static int paint(ChunkAccess chunk, ChunkMask m) {
        if (!m.any()) return 0;
        int painted = 0;
        int bx = m.cx << 4, bz = m.cz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        GenContext.bypass(true);
        try {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = ChunkMask.idx(lx, lz);
                    float in = m.in[i];
                    if (in < 0) continue;
                    int x = bx + lx, z = bz + lz;
                    double q = m.surface(i);
                    int top = (int) Math.floor(q);
                    boolean half = q - top >= 0.5;
                    if (top <= chunk.getMinY() + 2 || top >= chunk.getMaxY() - 6) continue;
                    int ground = groundAt(chunk, pos, x, z, top);
                    BlockState natural = chunk.getBlockState(pos.set(x, ground, z));
                    long h = hash(x, z);

                    for (int y = ground + 1; y < top; y++) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, top - y <= 3 ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 0);
                    }
                    pos.set(x, top, z);
                    chunk.setBlockState(pos, Palette.surface(in, h, natural, ground == top), 0);
                    int clearFrom = top + 1;
                    if (half) {
                        pos.set(x, top + 1, z);
                        chunk.setBlockState(pos, Palette.step(h).setValue(SlabBlock.TYPE, SlabType.BOTTOM), 0);
                        clearFrom++;
                    }
                    int clearTo = Math.max(top + 4, ground + 2);
                    for (int y = clearFrom; y <= clearTo; y++) {
                        pos.set(x, y, z);
                        if (!chunk.getBlockState(pos).isAir()) chunk.setBlockState(pos, AIR, 0);
                    }
                    painted++;
                }
            }
        } finally {
            GenContext.bypass(false);
        }
        return painted;
    }

    /** Top block of the terrain itself (solid, not a tree), searched near the planned surface. */
    static int groundAt(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int top) {
        int hi = Math.min(chunk.getMaxY() - 1, top + 24), lo = Math.max(chunk.getMinY(), top - 40);
        for (int y = hi; y >= lo; y--) {
            BlockState s = chunk.getBlockState(pos.set(x, y, z));
            if (isTerrain(s)) return y;
        }
        return lo;
    }

    public static boolean isTerrain(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.isSolid() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
    }

    static long hash(int x, int z) {
        long h = x * 0x2545F4914F6CDD1DL ^ z * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }
}
