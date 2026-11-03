package io.github.profetgit.countryroads.platform.neoforge;

//? neoforge {
/*import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.DoorUse;
import io.github.profetgit.countryroads.path.PathBlocks;
import io.github.profetgit.countryroads.path.PathNotice;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.MixinStore;
import io.github.profetgit.countryroads.world.RoadColumns;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(CountryRoads.ID)
public final class NeoForgeEntry {
    public NeoForgeEntry(IEventBus modBus) {
        RoadColumns.store = new MixinStore();
        CountryRoads.init("neoforge", FMLPaths.CONFIGDIR.get());
        modBus.addListener(NeoForgeEntry::register);
        modBus.addListener(NeoForgeEntry::tabs);
        modBus.addListener(RegisterPayloadHandlersEvent.class, NeoForgeEntry::payloads);
        PathNotice.CAN_SEND = p -> p.connection != null && p.connection.hasChannel(PathNotice.TYPE);
        PathNotice.SEND = PacketDistributor::sendToPlayer;

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerStartingEvent.class, e -> CountryRoads.serverStarting(e.getServer()));
        bus.addListener(RegisterCommandsEvent.class, e -> CountryRoads.commands(e.getDispatcher()));
        bus.addListener(EntityJoinLevelEvent.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && PathBlocks.droppedItem(e.getEntity(), level)) e.setCanceled(true);
        });
        bus.addListener(BreakBlockEvent.class, e -> {
            if (e.getPlayer() != null && e.getLevel() instanceof ServerLevel level && !PathBlocks.beforeBreak(level, e.getPlayer(), e.getPos(), e.getState())) e.setCanceled(true);
        });
        bus.addListener(PlayerInteractEvent.RightClickBlock.class, e -> {
            InteractionResult r = DoorUse.use(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec());
            if (r != InteractionResult.PASS) {
                e.setCancellationResult(r);
                e.setCanceled(true);
            }
        });
        bus.addListener(PlayerInteractEvent.LeftClickBlock.class, e -> {
            if (e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                && DoorUse.attack(e.getEntity(), e.getLevel(), e.getHand(), e.getPos()) != InteractionResult.PASS) e.setCanceled(true);
        });
        bus.addListener(ServerTickEvent.Post.class, e -> CountryRoads.serverTick(e.getServer()));
        bus.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level) CountryRoads.levelTick(level);
        });
        bus.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level) Retrofit.onLoad(level, e.getChunk(), e.isNewChunk());
        });
        bus.addListener(ServerStoppingEvent.class, e -> CountryRoads.serverStopping(e.getServer()));
        bus.addListener(ServerStoppedEvent.class, e -> CountryRoads.serverStopped(e.getServer()));
        bus.addListener(OnDatapackSyncEvent.class, e -> {
            if (e.getPlayer() == null) CountryRoads.dataReloaded(e.getPlayerList().getServer());
        });
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
                event.register((ResourceKey) e.registry(), e.key().identifier(), () -> e.value());
            }
        }
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(PathNotice.TYPE, PathNotice.CODEC, (p, ctx) -> PathNotice.RECEIVE.accept(p));
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(Stake.ITEM);
    }
}
*///?}
