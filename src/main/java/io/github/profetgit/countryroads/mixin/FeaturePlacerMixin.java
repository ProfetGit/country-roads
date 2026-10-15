package io.github.profetgit.countryroads.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.profetgit.countryroads.gen.GenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;
import net.minecraft.world.level.levelgen.feature.IcebergFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.RootSystemFeature;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
        int radius = bigRadius(feature);
        if (radius >= 0 && GenContext.blocksBigFeature(pos, radius, feature instanceof AbstractHugeMushroomFeature)) return false;
        if (radius != 0) return original.call(feature, level, generator, random, pos);
        // trees and mushrooms are placed whole or not at all: one the road would cut is taken back
        boolean owner = GenContext.beginTree();
        boolean placed = false;
        try {
            placed = original.call(feature, level, generator, random, pos);
        } finally {
            if (GenContext.endTree(level, owner)) placed = false;
        }
        return placed;
    }

    private static int bigRadius(Feature f) {
        if (f instanceof TreeFeature || f instanceof FallenTreeFeature || f instanceof AbstractHugeMushroomFeature
            || f instanceof HugeFungusFeature || f instanceof RootSystemFeature) return 0;
        if (f instanceof BlockBlobFeature || f instanceof SpikeFeature || f instanceof IcebergFeature) return 3;
        if (f instanceof LakeFeature) return 8;
        return MODDED_WOODY.get(f.getClass()) ? 0 : -1;
    }

    /** Modded trees often implement Feature directly (Biomes O' Plenty's BOPTreeFeature): judged by class name. */
    private static final ClassValue<Boolean> MODDED_WOODY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            if (c.getName().startsWith("net.minecraft.")) return false;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                String n = k.getSimpleName();
                if (n.contains("Tree") || n.contains("Log") || n.contains("Mushroom") || n.contains("Fungus")) return true;
            }
            return false;
        }
    };
}
