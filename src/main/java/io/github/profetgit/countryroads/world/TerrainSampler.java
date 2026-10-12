package io.github.profetgit.countryroads.world;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Ground and water height of a column before it generates, from the level's own generator (works with any noise
 * worldgen). Thread-safe; samples are memoised.
 */
public final class TerrainSampler {
    /** ground: Y of the top solid block; water: Y of the top fluid block above ground, or ground when dry. */
    public record Sample(int ground, int water, boolean ocean, boolean river) {
        public boolean wet() {
            return water > ground;
        }

        public int depth() {
            return water - ground;
        }
    }

    private static final int MAX_CACHE = 400_000;

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final ThreadLocal<BiomeResolver> biomes;
    private final ConcurrentHashMap<Long, Sample> cache = new ConcurrentHashMap<>();

    public TerrainSampler(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.biomes = ThreadLocal.withInitial(() -> generator.getBiomeSource().createCachingResolver(randomState));
    }

    public int minY() {
        return level.getMinY();
    }

    public int maxY() {
        return level.getMaxY();
    }

    public int seaLevel() {
        return generator.getSeaLevel();
    }

    public Sample sample(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Sample s = cache.get(key);
        if (s != null) return s;
        s = compute(x, z);
        if (cache.size() > MAX_CACHE) cache.clear();
        cache.put(key, s);
        return s;
    }

    public Holder<Biome> biome(int x, int y, int z) {
        return biomes.get().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    private Sample compute(int x, int z) {
        NoiseColumn col = generator.getBaseColumn(x, z, level, randomState);
        int min = level.getMinY();
        int water = Integer.MIN_VALUE;
        int ground = min;
        for (int y = level.getMaxY(); y >= min; y--) {
            BlockState s = col.getBlock(y);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) {
                if (water == Integer.MIN_VALUE) water = y;
                continue;
            }
            ground = y;
            break;
        }
        if (water == Integer.MIN_VALUE) water = ground;
        Holder<Biome> b = biome(x, Math.max(ground, water), z);
        return new Sample(ground, water, b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN), b.is(BiomeTags.IS_RIVER));
    }
}
