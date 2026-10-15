package io.github.profetgit.countryroads.mixin;

import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerMixin {
    /** No hostile mob spawns on the road surface (caves below a road are untouched). */
    @Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"), cancellable = true)
    private static void countryroads$noMonstersOnRoads(ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator,
                                                       MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double playerDistSqr,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (category != MobCategory.MONSTER || !io.github.profetgit.countryroads.Config.get().noMonstersOnRoads) return;
        if (!RoadColumns.isRoad(level, pos.getX(), pos.getZ())) return;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() >= surface - 6) cir.setReturnValue(false);
    }
}
