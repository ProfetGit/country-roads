package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.CountryRoads;
import java.util.Set;
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
    /** Set once by {@link #register} (the loader calls it while its registries are open). */
    public static Block BLOCK;
    public static Item ITEM;
    public static BlockEntityType<StakeBlockEntity> ENTITY;

    private Stake() {}

    public static void register(CountryRoads.Registrar r) {
        ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, ID);
        //? if >=26.3 {
        PushReaction reaction = PushReaction.POPPED;
        //?} else {
        /*PushReaction reaction = PushReaction.DESTROY;
        *///?}
        BLOCK = r.add(Registries.BLOCK, bk, new StakeBlock(BlockBehaviour.Properties.of().setId(bk)
            .strength(0.3f).sound(SoundType.WOOD).noOcclusion().pushReaction(reaction)));
        ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, ID);
        ITEM = r.add(Registries.ITEM, ik, new StakeItem(BLOCK, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
        ENTITY = r.add(Registries.BLOCK_ENTITY_TYPE, ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, ID), blockEntity());
    }

    private static BlockEntityType<StakeBlockEntity> blockEntity() {
        //? if >=26.1 {
        return new BlockEntityType<>(StakeBlockEntity::new, Set.of(BLOCK));
        //?} else {
        /*return BlockEntityType.Builder.of(StakeBlockEntity::new, BLOCK).build(null);
        *///?}
    }
}
