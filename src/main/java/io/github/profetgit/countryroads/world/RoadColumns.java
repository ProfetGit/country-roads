package io.github.profetgit.countryroads.world;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.stream.LongStream;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Which columns of a chunk are road, saved with the chunk (Fabric attachment, our own chunk NBT key elsewhere: {@link Store}): bits 0-255 any road column, bits
 * 256-511 open-sky road (not a tunnel). Written once by the painter; read by perks at runtime, never needing a plan.
 */
public record RoadColumns(long[] bits) {
    public static final Codec<RoadColumns> CODEC = Codec.LONG_STREAM.xmap(s -> new RoadColumns(s.toArray()), r -> LongStream.of(r.bits));
    /** Where a chunk keeps its road columns; set by the loader entry. */
    public interface Store {
        RoadColumns get(ChunkAccess chunk);

        void set(ChunkAccess chunk, RoadColumns cols);
    }

    public static Store store;

    public static RoadColumns get(ChunkAccess chunk) {
        return store.get(chunk);
    }

    public static RoadColumns empty() {
        return new RoadColumns(new long[8]);
    }

    public void set(int lx, int lz, boolean open) {
        int i = (lz << 4) | lx;
        bits[i >> 6] |= 1L << (i & 63);
        if (open) bits[4 + (i >> 6)] |= 1L << (i & 63);
    }

    public void unset(int lx, int lz) {
        int i = (lz << 4) | lx;
        bits[i >> 6] &= ~(1L << (i & 63));
        bits[4 + (i >> 6)] &= ~(1L << (i & 63));
    }

    /** Marks (or unmarks) a column of a loaded chunk as road at runtime (house paths). */
    public static void mark(Level level, int x, int z, boolean road) {
        ChunkAccess c = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (!(c instanceof LevelChunk chunk)) return;
        RoadColumns r = store.get(chunk);
        RoadColumns copy = new RoadColumns(r == null ? new long[8] : Arrays.copyOf(r.bits, 8));
        if (road) copy.set(x & 15, z & 15, true);
        else copy.unset(x & 15, z & 15);
        store.set(chunk, copy);
        chunk.markUnsaved();
    }

    public boolean road(int lx, int lz) {
        int i = (lz << 4) | lx;
        return bits.length >= 4 && (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    public boolean open(int lx, int lz) {
        int i = (lz << 4) | lx;
        return bits.length >= 8 && (bits[4 + (i >> 6)] & (1L << (i & 63))) != 0;
    }

    public boolean any() {
        return Arrays.stream(bits).anyMatch(b -> b != 0);
    }

    /** Always stored, empty too: its presence means "Country Roads has handled this chunk" (old-world retrofit skips it). */
    public static void store(ChunkAccess chunk, RoadColumns cols) {
        store.set(chunk, cols);
    }

    public static boolean handled(ChunkAccess chunk) {
        return store.get(chunk) != null;
    }

    /** Road column at (x, z) if that chunk is loaded; never loads or generates a chunk. */
    public static RoadColumns at(Level level, int x, int z) {
        ChunkAccess c = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (!(c instanceof LevelChunk chunk)) return null;
        return store.get(chunk);
    }

    public static boolean isRoad(Level level, int x, int z) {
        RoadColumns r = at(level, x, z);
        return r != null && r.road(x & 15, z & 15);
    }

    public static boolean isOpenRoad(Level level, int x, int z) {
        RoadColumns r = at(level, x, z);
        return r != null && r.open(x & 15, z & 15);
    }
}
