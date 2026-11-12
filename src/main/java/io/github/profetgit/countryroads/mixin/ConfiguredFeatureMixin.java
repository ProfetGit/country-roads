package io.github.profetgit.countryroads.mixin;

//? if >=26.3 {
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FeaturePlacer.class)
abstract class ConfiguredFeatureMixin {
}
//?} else {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ConfiguredFeature.class)
abstract class ConfiguredFeatureMixin {
    @WrapMethod(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z")
    private boolean countryroads$skipBig(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos, Operation<Boolean> original) {
        Feature<?> feature = ((ConfiguredFeature<?, ?>) (Object) this).feature();
        return io.github.profetgit.countryroads.gen.FeatureRules.skipBig(feature, level, pos, () -> original.call(level, generator, random, pos));
    }
}
*///?}
