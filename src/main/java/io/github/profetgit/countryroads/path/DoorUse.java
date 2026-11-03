package io.github.profetgit.countryroads.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Surveyor's Stake as a tool: right-click a door or fence gate with it (the stake is not used up) to survey a path
 * from the cell outside, right-click again to build it. On a built path, sneak + right-click keeps it for good and a
 * left-click on the door with the stake takes it up again (breaking the stake does the same for a ground stake).
 */
public final class DoorUse {
    private DoorUse() {}

    /** A player right-clicks a block (both sides); anything but PASS ends the click. */
    public static InteractionResult use(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        {
            if (player.isSpectator() || !player.getItemInHand(hand).is(Stake.ITEM)) return InteractionResult.PASS;
            if (player.isShiftKeyDown() && world.getBlockState(hit.getBlockPos()).is(Stake.BLOCK)) {
                // the stake in hand on a stake: keep a built path, or toggle "end at last stake" on an unbuilt one
                if (world instanceof ServerLevel level && player instanceof ServerPlayer sp) {
                    var d = HousePaths.data(level, hit.getBlockPos());
                    if (d == null) return InteractionResult.PASS;
                    if (d.status == PathData.BUILT) HousePaths.keep(level, hit.getBlockPos(), sp);
                    else HousePaths.toggleEnd(level, hit.getBlockPos(), sp);
                }
                return InteractionResult.SUCCESS;
            }
            if (!HousePaths.isDoorLike(world.getBlockState(hit.getBlockPos()))) return InteractionResult.PASS;
            if (world instanceof ServerLevel level && player instanceof ServerPlayer sp) {
                Vec3 c = Vec3.atCenterOf(hit.getBlockPos());
                HousePaths.useDoor(level, hit.getBlockPos(), sp, Direction.getApproximateNearest(sp.getX() - c.x, 0, sp.getZ() - c.z));
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** A player starts to break a block (both sides); anything but PASS stops the break. */
    public static InteractionResult attack(Player player, Level world, InteractionHand hand, BlockPos pos) {
        {
            if (player.isSpectator() || !player.getItemInHand(hand).is(Stake.ITEM) || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!HousePaths.isDoorLike(level.getBlockState(pos))) return InteractionResult.PASS;
            return HousePaths.undoDoor(level, pos, sp) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
    }
}
