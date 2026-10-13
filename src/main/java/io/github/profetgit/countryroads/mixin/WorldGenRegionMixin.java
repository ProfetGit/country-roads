package io.github.profetgit.countryroads.mixin;

import io.github.profetgit.countryroads.gen.GenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
abstract class WorldGenRegionMixin {
    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void countryroads$guard(BlockPos pos, BlockState state, int flags, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
        if (GenContext.guardWrite((WorldGenRegion) (Object) this, pos, state)) cir.setReturnValue(false);
    }
}
