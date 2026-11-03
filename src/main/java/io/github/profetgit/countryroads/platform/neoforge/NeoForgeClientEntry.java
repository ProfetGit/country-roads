package io.github.profetgit.countryroads.platform.neoforge;

//? neoforge {
/*import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.client.ConfigScreen;
import io.github.profetgit.countryroads.client.CountryRoadsClient;
import io.github.profetgit.countryroads.path.PathNotice;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@Mod(value = CountryRoads.ID, dist = Dist.CLIENT)
public final class NeoForgeClientEntry {
    public NeoForgeClientEntry(IEventBus modBus, ModContainer container) {
        PathNotice.RECEIVE = CountryRoadsClient::receive;
        modBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR,
            Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_card"), CountryRoadsClient::drawHud));
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigScreen(parent));
    }
}
*///?}
