package io.github.profetgit.countryroads;

import io.github.profetgit.countryroads.dev.DevCommands;
import io.github.profetgit.countryroads.perk.RoadSpeed;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.fabricmc.api.ModInitializer;
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
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> DevCommands.register(dispatcher));
        RoadColumns.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> DevCommands.tick());
        ServerTickEvents.END_LEVEL_TICK.register(RoadSpeed::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RoadNetwork.clear());
        LOG.info("Country Roads loaded");
    }
}
