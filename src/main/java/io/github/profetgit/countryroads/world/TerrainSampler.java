package io.github.profetgit.countryroads.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** Ground and water height of a column before it generates, from the level's own generator (works with any noise worldgen). */
public final class TerrainSampler {
    /** ground: Y of the top solid block; water: Y of the top fluid block above ground, or ground when dry. */
    public record Sample(int ground, int water) {
        public boolean wet() {
            return water > ground;
        }
    }

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;

    public TerrainSampler(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
    }

    public Sample sample(int x, int z) {
        NoiseColumn col = generator.getBaseColumn(x, z, level, randomState);
        int min = level.getMinY();
        int top = level.getMaxY();
        int water = Integer.MIN_VALUE;
        for (int y = top; y >= min; y--) {
            BlockState s = col.getBlock(y);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) {
                if (water == Integer.MIN_VALUE) water = y;
                continue;
            }
            return new Sample(y, water == Integer.MIN_VALUE ? y : water);
        }
        return new Sample(min, water == Integer.MIN_VALUE ? min : water);
    }
}
