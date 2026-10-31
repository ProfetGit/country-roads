package io.github.profetgit.countryroads;

import io.github.profetgit.countryroads.dev.DevCommands;
import io.github.profetgit.countryroads.perk.RoadSpeed;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.RoadColumns;
import io.github.profetgit.countryroads.retro.Retrofit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CountryRoads implements ModInitializer {
    public static final String ID = "countryroads";
    public static final Logger LOG = LoggerFactory.getLogger("Country Roads");

    @Override
    public void onInitialize() {
        Config.load();
        ServerLifecycleEvents.SERVER_STARTING.register(Config::worldStarting);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> DevCommands.register(dispatcher));
        RoadColumns.init();
        io.github.profetgit.countryroads.path.PathNotice.register();
        io.github.profetgit.countryroads.path.Stake.init();
        io.github.profetgit.countryroads.path.HousePaths.init();
        io.github.profetgit.countryroads.path.PathBlocks.init();
        io.github.profetgit.countryroads.path.DoorUse.init();
        ServerTickEvents.END_SERVER_TICK.register(io.github.profetgit.countryroads.path.HousePaths::tick);
        ServerTickEvents.END_SERVER_TICK.register(server -> DevCommands.tick());
        ServerTickEvents.END_LEVEL_TICK.register(RoadSpeed::tick);
        ServerTickEvents.END_SERVER_TICK.register(server -> Retrofit.tick());
        ServerTickEvents.END_SERVER_TICK.register(io.github.profetgit.countryroads.plan.Prefetch::tick);
        ServerChunkEvents.CHUNK_LOAD.register(Retrofit::onLoad);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.profetgit.countryroads.path.HousePaths.stop());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            RoadNetwork.clear();
            Config.worldStopped();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, ok) -> io.github.profetgit.countryroads.path.HousePaths.reloaded());
        LOG.info("Country Roads loaded");
    }
}
