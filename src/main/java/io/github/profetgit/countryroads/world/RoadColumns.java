package io.github.profetgit.countryroads.world;

import com.mojang.serialization.Codec;
import io.github.profetgit.countryroads.CountryRoads;
import java.util.Arrays;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Which columns of a chunk are road, saved with the chunk (Fabric attachment): bits 0-255 any road column, bits
 * 256-511 open-sky road (not a tunnel). Written once by the painter; read by perks at runtime, never needing a plan.
 */
public record RoadColumns(long[] bits) {
    public static final Codec<RoadColumns> CODEC = Codec.LONG_STREAM.xmap(s -> new RoadColumns(s.toArray()), r -> LongStream.of(r.bits));
    public static final AttachmentType<RoadColumns> TYPE = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(CountryRoads.ID, "road_columns"), b -> b.persistent(CODEC));

    public static void init() {
    }

    public static RoadColumns empty() {
        return new RoadColumns(new long[8]);
    }

    public void set(int lx, int lz, boolean open) {
        int i = (lz << 4) | lx;
        bits[i >> 6] |= 1L << (i & 63);
        if (open) bits[4 + (i >> 6)] |= 1L << (i & 63);
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

    public static void store(ChunkAccess chunk, RoadColumns cols) {
        if (cols.any()) ((AttachmentTarget) chunk).setAttached(TYPE, cols);
    }

    /** Road column at (x, z) if that chunk is loaded; never loads or generates a chunk. */
    public static RoadColumns at(Level level, int x, int z) {
        ChunkAccess c = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (!(c instanceof LevelChunk chunk)) return null;
        return ((AttachmentTarget) chunk).getAttached(TYPE);
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
