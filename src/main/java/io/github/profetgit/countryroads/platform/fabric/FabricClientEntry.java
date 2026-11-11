package io.github.profetgit.countryroads.platform.fabric;

//? fabric {
import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.client.CountryRoadsClient;
import io.github.profetgit.countryroads.path.PathNotice;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//?}
import net.minecraft.resources.Identifier;

public final class FabricClientEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if >=1.21 {
        ClientPlayNetworking.registerGlobalReceiver(PathNotice.TYPE, (notice, context) -> CountryRoadsClient.receive(notice));
        //?} else {
        /*ClientPlayNetworking.registerGlobalReceiver(PathNotice.ID, (client, handler, buf, sender) -> {
            PathNotice notice = PathNotice.read(buf);
            client.execute(() -> CountryRoadsClient.receive(notice));
        });
        *///?}
        //? if >=26.1 {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_card"), CountryRoadsClient::drawHud);
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(CountryRoadsClient::drawHud);
        *///?}
    }
}
//?}
