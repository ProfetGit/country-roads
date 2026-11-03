package io.github.profetgit.countryroads.chunkmixin;

import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import io.github.profetgit.countryroads.world.RoadColumnsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Road columns in the chunk's NBT under {@link #KEY}: copied from the chunk on the server thread (copyOf), written off
 * it (write), parsed off it (parse) and handed to the chunk on load (read).
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin implements RoadColumnsHolder {
    @Unique
    private static final String KEY = "countryroads:road_columns";

    @Unique
    private RoadColumns countryroads$cols;

    @Override
    public RoadColumns countryroads$cols() {
        return countryroads$cols;
    }

    @Override
    public void countryroads$setCols(RoadColumns cols) {
        countryroads$cols = cols;
    }

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void countryroads$copy(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> cir) {
        RoadColumns cols = RoadColumns.get(chunk);
        if (cols != null) ((RoadColumnsHolder) (Object) cir.getReturnValue()).countryroads$setCols(new RoadColumns(java.util.Arrays.copyOf(cols.bits(), cols.bits().length)));
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void countryroads$write(CallbackInfoReturnable<CompoundTag> cir) {
        if (countryroads$cols != null) cir.getReturnValue().putLongArray(KEY, countryroads$cols.bits());
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void countryroads$parse(LevelHeightAccessor height, PalettedContainerFactory factory, CompoundTag tag, CallbackInfoReturnable<SerializableChunkData> cir) {
        if (cir.getReturnValue() == null) return;
        tag.getLongArray(KEY).ifPresent(bits -> ((RoadColumnsHolder) (Object) cir.getReturnValue()).countryroads$setCols(new RoadColumns(bits)));
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void countryroads$read(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CallbackInfoReturnable<ProtoChunk> cir) {
        if (countryroads$cols != null) RoadColumns.store.set(cir.getReturnValue(), countryroads$cols);
    }
}
