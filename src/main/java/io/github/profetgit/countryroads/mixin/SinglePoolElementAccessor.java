package io.github.profetgit.countryroads.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A pool element's processor list: modded village streets are recognised by it (e.g. nova_structures:street_birch). */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor {
    @Accessor("processors")
    Holder<StructureProcessorList> countryroads$processors();
}
