package io.github.profetgit.countryroads.platform.fabric;

//? fabric {
import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.client.CountryRoadsClient;
import io.github.profetgit.countryroads.path.PathNotice;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

public final class FabricClientEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PathNotice.TYPE, (notice, context) -> CountryRoadsClient.receive(notice));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_card"), CountryRoadsClient::drawHud);
    }
}
//?}
