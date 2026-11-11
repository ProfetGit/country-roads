package io.github.profetgit.countryroads.mixin;

//? if >=26.3 {
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FeaturePlacer.class)
abstract class ConfiguredFeatureMixin {
}
//?} else {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ConfiguredFeature.class)
abstract class ConfiguredFeatureMixin {
    @WrapOperation(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
        require = 1)
    private boolean countryroads$skipBig(Feature<?> feature, FeatureConfiguration config, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos, Operation<Boolean> original) {
        return io.github.profetgit.countryroads.gen.FeatureRules.skipBig(feature, level, pos, () -> original.call(feature, config, level, generator, random, pos));
    }
}
*///?}
