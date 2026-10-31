package io.github.profetgit.countryroads.client;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.PathNotice;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/** Client entry: the stake path card beside the crosshair. */
public final class CountryRoadsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PathNotice.TYPE, (notice, context) -> PathHud.receive(notice));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_card"), PathHud::draw);
    }
}
