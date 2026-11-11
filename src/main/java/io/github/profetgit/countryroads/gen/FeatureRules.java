package io.github.profetgit.countryroads.gen;

import io.github.profetgit.countryroads.gen.GenContext;
import java.util.function.Supplier;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.BambooFeature;
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
//? if >=26.1 {
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
//?}
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;
import net.minecraft.world.level.levelgen.feature.IcebergFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.RootSystemFeature;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

public final class FeatureRules {
    private FeatureRules() {}

    public static boolean skipBig(Object feature, WorldGenLevel level, net.minecraft.core.BlockPos pos, Supplier<Boolean> original) {
        int radius = bigRadius(feature);
        if (radius >= 0 && GenContext.blocksBigFeature(pos, radius, feature instanceof AbstractHugeMushroomFeature)) return false;
        if (radius != 0) return original.get();
        boolean owner = GenContext.beginTree();
        boolean placed = false;
        try {
            placed = original.get();
        } finally {
            if (GenContext.endTree(level, owner)) placed = false;
        }
        return placed;
    }

    static int bigRadius(Object f) {
        //? if >=26.1 {
        if (f instanceof FallenTreeFeature) return 0;
        //?}
        if (f instanceof TreeFeature || f instanceof AbstractHugeMushroomFeature
            || f instanceof HugeFungusFeature || f instanceof RootSystemFeature || f instanceof BambooFeature || f instanceof BlockColumnFeature) return 0;
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
