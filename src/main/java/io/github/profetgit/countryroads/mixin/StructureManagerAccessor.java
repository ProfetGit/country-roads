package io.github.profetgit.countryroads.mixin;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.20.1: createStructures gets no level, only the StructureManager that wraps it (used by the camp hook)
@Mixin(StructureManager.class)
public interface StructureManagerAccessor {
    @Accessor("level")
    LevelAccessor countryroads$level();
}
