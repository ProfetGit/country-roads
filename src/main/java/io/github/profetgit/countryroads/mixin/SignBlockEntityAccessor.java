package io.github.profetgit.countryroads.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sign text for a sign made during worldgen, where the entity has no level yet (setText would notify the level). */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
    @Accessor("frontText")
    void countryroads$setFront(SignText text);

    @Accessor("backText")
    void countryroads$setBack(SignText text);

    @Accessor("isWaxed")
    void countryroads$setWaxed(boolean waxed);
}
