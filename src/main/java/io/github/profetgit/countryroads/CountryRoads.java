package io.github.profetgit.countryroads;

import io.github.profetgit.countryroads.dev.DevCommands;
import net.fabricmc.api.ModInitializer;
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
        ServerTickEvents.END_SERVER_TICK.register(server -> DevCommands.tick());
        LOG.info("Country Roads loaded");
    }
}
