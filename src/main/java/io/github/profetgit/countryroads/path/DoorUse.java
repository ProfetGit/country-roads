package io.github.profetgit.countryroads.path;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Surveyor's Stake as a tool: right-click a door or fence gate with it (the stake is not used up) to survey a path
 * from the cell outside, right-click again to build it. A built path answers with two chat buttons (`/roadpath keep|undo`).
 */
public final class DoorUse {
    private DoorUse() {}

    public static void init() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player.isSpectator() || !player.getItemInHand(hand).is(Stake.ITEM)) return InteractionResult.PASS;
            if (!HousePaths.isDoorLike(world.getBlockState(hit.getBlockPos()))) return InteractionResult.PASS;
            if (world instanceof ServerLevel level && player instanceof ServerPlayer sp) {
                Vec3 c = Vec3.atCenterOf(hit.getBlockPos());
                HousePaths.useDoor(level, hit.getBlockPos(), sp, Direction.getApproximateNearest(sp.getX() - c.x, 0, sp.getZ() - c.z));
            }
            return InteractionResult.SUCCESS;
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("roadpath")
            .then(Commands.literal("keep").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c, true))))
            .then(Commands.literal("undo").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c, false)))));
    }

    private static int run(CommandContext<CommandSourceStack> c, boolean keep) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        HousePaths.answer(c.getSource().getLevel(), pos, c.getSource().getPlayerOrException(), keep);
        return 1;
    }
}
