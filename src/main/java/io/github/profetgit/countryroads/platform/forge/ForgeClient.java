package io.github.profetgit.countryroads.platform.forge;

//? forge {
/*import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.client.ConfigScreen;
import io.github.profetgit.countryroads.client.CountryRoadsClient;
import io.github.profetgit.countryroads.path.PathNotice;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/^* Client only (ForgeEntry calls it on the client). ^/
final class ForgeClient {
    private ForgeClient() {
    }

    static void register(FMLJavaModLoadingContext context) {
        PathNotice.RECEIVE = CountryRoadsClient::receive;
        AddGuiOverlayLayersEvent.BUS.addListener(
            event -> event.getLayeredDraw().add(Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_card"), CountryRoadsClient::drawHud));
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConfigScreen(parent)));
    }
}
*///?}
