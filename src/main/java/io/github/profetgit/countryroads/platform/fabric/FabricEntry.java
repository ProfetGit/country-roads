package io.github.profetgit.countryroads.platform.fabric;

//? fabric {
import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.DoorUse;
import io.github.profetgit.countryroads.path.PathBlocks;
import io.github.profetgit.countryroads.path.PathNotice;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class FabricEntry implements ModInitializer {
    /** Road columns saved with the chunk; the id and codec are what 1.0.0-beta.1 worlds hold. */
    static final AttachmentType<RoadColumns> ROAD_COLUMNS = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(CountryRoads.ID, "road_columns"), b -> b.persistent(RoadColumns.CODEC));

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void onInitialize() {
        RoadColumns.store = new RoadColumns.Store() {
            @Override
            public RoadColumns get(ChunkAccess chunk) {
                return ((AttachmentTarget) chunk).getAttached(ROAD_COLUMNS);
            }

            @Override
            public void set(ChunkAccess chunk, RoadColumns cols) {
                ((AttachmentTarget) chunk).setAttached(ROAD_COLUMNS, cols);
            }
        };
        CountryRoads.init("fabric", FabricLoader.getInstance().getConfigDir());
        CountryRoads.register(new CountryRoads.Registrar() {
            @Override
            public <B, T extends B> T add(ResourceKey<? extends Registry<B>> registry, ResourceKey<B> key, T value) {
                Registry<B> target = (Registry<B>) BuiltInRegistries.REGISTRY.getValueOrThrow((ResourceKey) registry);
                return Registry.register(target, key, value);
            }
        });
        PayloadTypeRegistry.clientboundPlay().register(PathNotice.TYPE, PathNotice.CODEC);
        PathNotice.CAN_SEND = p -> ServerPlayNetworking.canSend(p, PathNotice.TYPE);
        PathNotice.SEND = ServerPlayNetworking::send;
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
            .register(out -> out.accept(Stake.ITEM));

        ServerLifecycleEvents.SERVER_STARTING.register(CountryRoads::serverStarting);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> CountryRoads.commands(dispatcher));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (PathBlocks.droppedItem(entity, world)) entity.discard();
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> PathBlocks.beforeBreak(world, player, pos, state));
        UseBlockCallback.EVENT.register(DoorUse::use);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> DoorUse.attack(player, world, hand, pos));
        ServerTickEvents.END_SERVER_TICK.register(CountryRoads::serverTick);
        ServerTickEvents.END_LEVEL_TICK.register(CountryRoads::levelTick);
        ServerChunkEvents.CHUNK_LOAD.register(Retrofit::onLoad);
        ServerLifecycleEvents.SERVER_STOPPING.register(CountryRoads::serverStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(CountryRoads::serverStopped);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, ok) -> CountryRoads.dataReloaded(server));
    }
}
//?}
