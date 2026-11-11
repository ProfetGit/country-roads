package io.github.profetgit.countryroads.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// dev perks check: reaches the private spawn test without reflection (Fabric before 26 runs intermediary names)
@Mixin(NaturalSpawner.class)
public interface NaturalSpawnerInvoker {
    @Invoker("isValidSpawnPostitionForType")
    static boolean countryroads$valid(ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator,
                                      MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double distSqr) {
        throw new AssertionError();
    }
}
