package io.github.profetgit.countryroads.platform.forge;

import io.github.profetgit.countryroads.client.ConfigScreen;
import io.github.profetgit.countryroads.client.CountryRoadsClient;
import io.github.profetgit.countryroads.path.PathNotice;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

// Client only (ForgeEntry calls it on the client)
final class ForgeClient {
    private ForgeClient() {
    }

    static void register(IEventBus modBus) {
        PathNotice.RECEIVE = CountryRoadsClient::receive;
        modBus.addListener((RegisterGuiOverlaysEvent event) -> event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "path_card",
            (gui, g, partialTick, width, height) -> CountryRoadsClient.drawHud(g, partialTick)));
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConfigScreen(parent)));
    }
}
