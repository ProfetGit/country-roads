package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import io.github.profetgit.countryroads.world.SavedDataCompat;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.stream.LongStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every column a built house path took, per dimension: a survey knows where the paths are (they count as roads to
 * join) without loading their chunks first. Server thread only.
 */
public final class HousePathIndex extends SavedData {
    public static final Codec<HousePathIndex> CODEC = Codec.LONG_STREAM.xmap(s -> new HousePathIndex(s.toArray()), h -> LongStream.of(h.columns.toLongArray()));

    private final LongOpenHashSet columns;

    public HousePathIndex() {
        this(new long[0]);
    }

    private HousePathIndex(long[] cols) {
        this.columns = new LongOpenHashSet(cols);
    }

    public static HousePathIndex of(ServerLevel level) {
        return SavedDataCompat.of(level, "house_paths_", CODEC, HousePathIndex::new);
    }

    //? if <26.1 {
    /*@Override
    public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        return SavedDataCompat.save(CODEC, this, tag, provider);
    }
    *///?}

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
