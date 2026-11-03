package io.github.profetgit.countryroads.world;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

/** NeoForge and Forge: road columns in a field the chunkmixin mixins add to every chunk (a loaded chunk's imposter passes through). */
public final class MixinStore implements RoadColumns.Store {
    private static ChunkAccess real(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk i ? i.getWrapped() : chunk;
    }

    @Override
    public RoadColumns get(ChunkAccess chunk) {
        return ((RoadColumnsHolder) real(chunk)).countryroads$cols();
    }

    @Override
    public void set(ChunkAccess chunk, RoadColumns cols) {
        ChunkAccess c = real(chunk);
        ((RoadColumnsHolder) c).countryroads$setCols(cols);
        c.markUnsaved();
    }
}
