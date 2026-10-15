package io.github.profetgit.countryroads.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MapItem.class)
abstract class MapItemMixin {
    /** Open-sky road columns are drawn in a light sand colour, so roads read as lines on a map. */
    @WrapOperation(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;getMapColor(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/MapColor;",
        ordinal = 3))
    private MapColor countryroads$roadColour(BlockState state, BlockGetter getter, BlockPos pos, Operation<MapColor> original) {
        MapColor c = original.call(state, getter, pos);
        if (getter instanceof Level level && io.github.profetgit.countryroads.Config.get().roadsOnMaps && state.getFluidState().isEmpty() && RoadColumns.isOpenRoad(level, pos.getX(), pos.getZ())) return MapColor.SAND;
        return c;
    }
}
