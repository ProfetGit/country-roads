package io.github.profetgit.countryroads.platform.forge;

//? forge {
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
import net.minecraft.world.level.chunk.LevelChunk;
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
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.registries.RegisterEvent;

@Mod(CountryRoads.ID)
public final class ForgeEntry {
    static SimpleChannel channel;

    public ForgeEntry(FMLJavaModLoadingContext context) {
        RoadColumns.store = new MixinStore();
        CountryRoads.init("forge", FMLPaths.CONFIGDIR.get());
        RegisterEvent.getBus(context.getModBusGroup()).addListener(ForgeEntry::register);
        BuildCreativeModeTabContentsEvent.BUS.addListener(ForgeEntry::tabs);
        channel = ChannelBuilder.named(CountryRoads.ID + ":main").networkProtocolVersion(1).optional().simpleChannel();
        channel.play().clientbound().addMain(PathNotice.class, PathNotice.CODEC, (msg, ctx) -> PathNotice.RECEIVE.accept(msg));
        PathNotice.CAN_SEND = p -> p.connection != null && channel.isRemotePresent(p.connection.getConnection());
        PathNotice.SEND = (p, n) -> channel.send(n, PacketDistributor.PLAYER.with(p));

        ServerStartingEvent.BUS.addListener(e -> CountryRoads.serverStarting(e.getServer()));
        RegisterCommandsEvent.BUS.addListener(e -> CountryRoads.commands(e.getDispatcher()));
        EntityJoinLevelEvent.BUS.addListener(e -> e.getLevel() instanceof ServerLevel level && PathBlocks.droppedItem(e.getEntity(), level));
        BlockEvent.BreakEvent.BUS.addListener(e -> e.getPlayer() != null && e.getLevel() instanceof ServerLevel level
            && !PathBlocks.beforeBreak(level, e.getPlayer(), e.getPos(), e.getState()));
        PlayerInteractEvent.RightClickBlock.BUS.addListener(e -> {
            InteractionResult r = DoorUse.use(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec());
            if (r == InteractionResult.PASS) return false;
            e.setCancellationResult(r);
            return true;
        });
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(e -> e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
            && DoorUse.attack(e.getEntity(), e.getLevel(), e.getHand(), e.getPos()) != InteractionResult.PASS);
        TickEvent.ServerTickEvent.Post.BUS.addListener(e -> CountryRoads.serverTick(e.server()));
        TickEvent.LevelTickEvent.Post.BUS.addListener(e -> {
            if (e.level() instanceof ServerLevel level) CountryRoads.levelTick(level);
        });
        ChunkEvent.Load.BUS.addListener(e -> {
            if (e.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ServerLevel level) Retrofit.onLoad(level, chunk, e.isNewChunk());
        });
        ServerStoppingEvent.BUS.addListener(e -> CountryRoads.serverStopping(e.getServer()));
        ServerStoppedEvent.BUS.addListener(e -> CountryRoads.serverStopped(e.getServer()));
        OnDatapackSyncEvent.BUS.addListener(e -> {
            if (e.getPlayer() == null) CountryRoads.dataReloaded(e.getPlayerList().getServer());
        });
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) ForgeClient.register(context);
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

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(Stake.ITEM);
    }
}
*///?}
