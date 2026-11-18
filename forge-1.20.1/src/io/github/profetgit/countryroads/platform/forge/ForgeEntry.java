package io.github.profetgit.countryroads.platform.forge;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.DoorUse;
import io.github.profetgit.countryroads.path.PathBlocks;
import io.github.profetgit.countryroads.path.PathNotice;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.platform.Platform;
import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.MixinStore;
import io.github.profetgit.countryroads.world.RoadColumns;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.RegisterEvent;

// Forge 47 (1.20.1): the 26.x Forge entry's wiring on this version's events; the channel accepts a missing peer, so vanilla clients still connect
@Mod("countryroads")
public final class ForgeEntry implements Platform {
    private static final String PROTOCOL = "1";
    static SimpleChannel channel;

    public ForgeEntry() {
        Platform.set(this);
        RoadColumns.store = new MixinStore();
        CountryRoads.init("forge", FMLPaths.CONFIGDIR.get());
        CountryRoads.modLoaded = id -> net.minecraftforge.fml.ModList.get().isLoaded(id);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ForgeEntry::register);
        modBus.addListener(ForgeEntry::tabs);
        channel = NetworkRegistry.newSimpleChannel(new net.minecraft.resources.ResourceLocation(CountryRoads.ID, "main"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));
        channel.messageBuilder(PathNotice.class, 0, NetworkDirection.PLAY_TO_CLIENT).encoder(PathNotice::write).decoder(PathNotice::read)
            .consumerMainThread((msg, ctx) -> PathNotice.RECEIVE.accept(msg)).add();
        PathNotice.CAN_SEND = p -> p.connection != null && channel.isRemotePresent(p.connection.connection);
        PathNotice.SEND = (p, n) -> channel.send(PacketDistributor.PLAYER.with(() -> p), n);

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ServerStartingEvent e) -> CountryRoads.serverStarting(e.getServer()));
        bus.addListener((RegisterCommandsEvent e) -> CountryRoads.commands(e.getDispatcher()));
        bus.addListener((EntityJoinLevelEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level && PathBlocks.droppedItem(e.getEntity(), level)) e.setCanceled(true);
        });
        bus.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getPlayer() != null && e.getLevel() instanceof ServerLevel level && !PathBlocks.beforeBreak(level, e.getPlayer(), e.getPos(), e.getState())) e.setCanceled(true);
        });
        bus.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            InteractionResult r = DoorUse.use(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec());
            if (r != InteractionResult.PASS) {
                e.setCancellationResult(r);
                e.setCanceled(true);
            }
        });
        bus.addListener((PlayerInteractEvent.LeftClickBlock e) -> {
            if (e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                && DoorUse.attack(e.getEntity(), e.getLevel(), e.getHand(), e.getPos()) != InteractionResult.PASS) e.setCanceled(true);
        });
        bus.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) CountryRoads.serverTick(e.getServer());
        });
        bus.addListener((TickEvent.LevelTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && e.level instanceof ServerLevel level) CountryRoads.levelTick(level);
        });
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ServerLevel level) Retrofit.onLoad(level, chunk, e.isNewChunk());
        });
        bus.addListener((ServerStoppingEvent e) -> CountryRoads.serverStopping(e.getServer()));
        bus.addListener((ServerStoppedEvent e) -> CountryRoads.serverStopped(e.getServer()));
        bus.addListener((OnDatapackSyncEvent e) -> {
            if (e.getPlayer() == null) CountryRoads.dataReloaded(e.getPlayerList().getServer());
        });
        if (FMLEnvironment.dist.isClient()) ForgeClient.register(modBus);
    }

    private record Entry(ResourceKey<? extends Registry<?>> registry, ResourceKey<?> key, Object value) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean built;

    // RegisterEvent fires once per registry: build the content once, then hand each registry its own entries.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(RegisterEvent event) {
        if (!built) {
            built = true;
            CountryRoads.register(new CountryRoads.Registrar() {
                @Override
                public <B, T extends B> T add(ResourceKey<? extends Registry<B>> registry, ResourceKey<B> key, T value) {
                    ENTRIES.add(new Entry(registry, key, value));
                    return value;
                }
            });
        }
        for (Entry e : ENTRIES) {
            if (e.registry().equals(event.getRegistryKey())) {
                event.register((ResourceKey) e.registry(), e.key().location(), () -> e.value());
            }
        }
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(Stake.ITEM);
    }

    @Override
    public String loader() {
        return "forge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public List<ModInfo> mods() {
        return ModList.get().getMods().stream().map(m -> new ModInfo(m.getModId(), m.getDisplayName())).toList();
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }
}
