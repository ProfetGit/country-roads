package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.CountryRoads;
import java.util.Set;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/** The Surveyor's Stake: block, item and block entity. */
public final class Stake {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(CountryRoads.ID, "surveyors_stake");
    public static final Block BLOCK;
    public static final Item ITEM;
    public static final BlockEntityType<StakeBlockEntity> ENTITY;

    static {
        ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, ID);
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, bk, new StakeBlock(BlockBehaviour.Properties.of().setId(bk)
            .strength(0.3f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.POPPED)));
        ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, ID);
        ITEM = Registry.register(BuiltInRegistries.ITEM, ik, new StakeItem(BLOCK, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
        ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID, new BlockEntityType<>(StakeBlockEntity::new, Set.of(BLOCK)));
    }

    private Stake() {}

    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
            .register(out -> out.accept(ITEM));
    }
}
