package io.github.profetgit.countryroads.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.profetgit.countryroads.gen.GenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
//? if >=26.3 {
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
//?}
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=26.3 {
@Mixin(FeaturePlacer.class)
abstract class FeaturePlacerMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"))
    private void countryroads$enter(PlacedFeature feature, RandomSource random, BlockPos origin, boolean biomeCheck, CallbackInfoReturnable<Boolean> cir) {
        GenContext.enterFeature();
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("RETURN"))
    private void countryroads$exit(PlacedFeature feature, RandomSource random, BlockPos origin, boolean biomeCheck, CallbackInfoReturnable<Boolean> cir) {
        GenContext.exitFeature();
    }

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 2)
    private boolean countryroads$skipBig(Feature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos, Operation<Boolean> original) {
        return io.github.profetgit.countryroads.gen.FeatureRules.skipBig(feature, level, pos, () -> original.call(feature, level, generator, random, pos));
    }
}
//?} else {
/*@Mixin(PlacedFeature.class)
abstract class FeaturePlacerMixin {
    @Inject(method = "placeWithContext", at = @At("HEAD"))
    private void countryroads$enter(net.minecraft.world.level.levelgen.placement.PlacementContext context, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        GenContext.enterFeature();
    }

    @Inject(method = "placeWithContext", at = @At("RETURN"))
    private void countryroads$exit(net.minecraft.world.level.levelgen.placement.PlacementContext context, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        GenContext.exitFeature();
    }
}
*///?}
