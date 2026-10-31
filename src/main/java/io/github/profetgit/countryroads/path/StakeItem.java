package io.github.profetgit.countryroads.path;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** The Surveyor's Stake: its tooltip is where the gestures are written down, so nothing has to be said in chat. */
public final class StakeItem extends BlockItem {
    public StakeItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, out, flag);
        for (int i = 1; i <= 4; i++) out.accept(Component.translatable("item.countryroads.surveyors_stake.tip" + i).withStyle(ChatFormatting.GRAY));
    }
}
