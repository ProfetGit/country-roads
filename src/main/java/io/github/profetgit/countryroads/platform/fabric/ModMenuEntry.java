package io.github.profetgit.countryroads.platform.fabric;

//? fabric {
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.profetgit.countryroads.client.ConfigScreen;

/** Mod Menu's settings button (only loaded when Mod Menu is installed). */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
//?}
