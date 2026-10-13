package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import io.github.profetgit.countryroads.CountryRoads;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every column a built house path took, per dimension: a survey knows where the paths are (they count as roads to
 * join) without loading their chunks first. Server thread only.
 */
public final class HousePathIndex extends SavedData {
    public static final Codec<HousePathIndex> CODEC = Codec.LONG_STREAM.xmap(s -> new HousePathIndex(s.toArray()), h -> LongStream.of(h.columns.toLongArray()));
    /** One type per dimension, cached: the storage keys by the type record. */
    private static final Map<String, SavedDataType<HousePathIndex>> TYPES = new ConcurrentHashMap<>();

    private final LongOpenHashSet columns;

    public HousePathIndex() {
        this(new long[0]);
    }

    private HousePathIndex(long[] cols) {
        this.columns = new LongOpenHashSet(cols);
    }

    private static SavedDataType<HousePathIndex> type(ServerLevel level) {
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return TYPES.computeIfAbsent(dim, d -> new SavedDataType<>(Identifier.fromNamespaceAndPath(CountryRoads.ID, "house_paths_" + d), HousePathIndex::new, CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE));
    }

    public static HousePathIndex of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(type(level));
    }

    public void set(int x, int z, boolean path) {
        if (path ? columns.add(ChunkPos.pack(x, z)) : columns.remove(ChunkPos.pack(x, z))) setDirty();
    }

    /** Columns within radius (square) of (x, z). */
    public long[] within(int x, int z, int radius) {
        LongArrayList out = new LongArrayList();
        for (long k : columns)
            if (Math.abs(ChunkPos.getX(k) - x) <= radius && Math.abs(ChunkPos.getZ(k) - z) <= radius) out.add(k);
        return out.toLongArray();
    }
}
