package io.github.profetgit.countryroads.chunkmixin;

import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.world.level.chunk.ChunkAccess;
import io.github.profetgit.countryroads.world.RoadColumnsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements RoadColumnsHolder {
    @Unique
    private volatile RoadColumns countryroads$cols;

    @Override
    public RoadColumns countryroads$cols() {
        return countryroads$cols;
    }

    @Override
    public void countryroads$setCols(RoadColumns cols) {
        countryroads$cols = cols;
    }
}
