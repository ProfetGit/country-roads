package io.github.profetgit.countryroads.path;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=26.1 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.block.Block;

/** The Surveyor's Stake: its tooltip is where the gestures are written down, so nothing has to be said in chat. */
public final class StakeItem extends BlockItem {
    public StakeItem(Block block, Properties properties) {
        super(block, properties);
    }

    //? if >=26.1 {
    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, out, flag);
        for (int i = 1; i <= 4; i++) out.accept(Component.translatable("item.countryroads.surveyors_stake.tip" + i).withStyle(ChatFormatting.GRAY));
    }
    //?} else if >=1.21 {
    /*@Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, java.util.List<Component> out, TooltipFlag flag) {
        super.appendHoverText(stack, context, out, flag);
        for (int i = 1; i <= 4; i++) out.add(Component.translatable("item.countryroads.surveyors_stake.tip" + i).withStyle(ChatFormatting.GRAY));
    }
    *///?} else {
    /*@Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level, java.util.List<Component> out, TooltipFlag flag) {
        super.appendHoverText(stack, level, out, flag);
        for (int i = 1; i <= 4; i++) out.add(Component.translatable("item.countryroads.surveyors_stake.tip" + i).withStyle(ChatFormatting.GRAY));
    }
    *///?}
}
