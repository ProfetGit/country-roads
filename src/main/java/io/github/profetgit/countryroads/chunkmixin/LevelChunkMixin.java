package io.github.profetgit.countryroads.chunkmixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import io.github.profetgit.countryroads.world.RoadColumnsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A generated chunk becoming a level chunk keeps the road columns the painter gave its proto chunk. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("TAIL"))
    private void countryroads$keepCols(ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci) {
        ((RoadColumnsHolder) this).countryroads$setCols(((RoadColumnsHolder) proto).countryroads$cols());
    }
}
