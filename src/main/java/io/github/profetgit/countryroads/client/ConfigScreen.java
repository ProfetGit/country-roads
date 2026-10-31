package io.github.profetgit.countryroads.client;

import io.github.profetgit.countryroads.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Settings on one page. Gameplay settings apply at once in singleplayer (and on a server this client hosts); a
 * dedicated server reads its own config/countryroads.json. World generation settings are the defaults for new worlds.
 */
public final class ConfigScreen extends OptionsSubScreen {
    public ConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("countryroads.config.title"));
    }

    @Override
    protected void addOptions() {
        Config c = Config.get();
        Config.Gen g = c.newWorlds;
        list.addHeader(Component.translatable("countryroads.config.header.gameplay"));
        list.addSmall(
            percent("player_speed", 5, 10, c.playerSpeed, v -> c.playerSpeed = v),
            percent("mount_speed", 5, 10, c.mountSpeed, v -> c.mountSpeed = v),
            bool("no_monsters", c.noMonstersOnRoads, v -> c.noMonstersOnRoads = v),
            bool("maps", c.roadsOnMaps, v -> c.roadsOnMaps = v),
            bool("house_paths", c.housePaths, v -> c.housePaths = v),
            bool("old_chunks", c.roadsInOldChunks, v -> c.roadsInOldChunks = v));
        list.addHeader(Component.translatable("countryroads.config.header.new_worlds"));
        list.addSmall(
            bool("streets", g.villageStreets, v -> g.villageStreets = v),
            bool("signposts", g.signposts, v -> g.signposts = v),
            bool("camps", g.waysideCamps, v -> g.waysideCamps = v),
            percent("props", 25, 12, g.roadsideProps, v -> g.roadsideProps = v),
            percent("wheat", 25, 12, g.wheatFields, v -> g.wheatFields = v));
    }

    private static OptionInstance<Boolean> bool(String key, boolean value, OptionInstance.ValueUpdateListener<Boolean> set) {
        return OptionInstance.createBoolean("countryroads.config." + key, OptionInstance.cachedConstantTooltip(Component.translatable("countryroads.config." + key + ".tip")), value, set);
    }

    /** A slider from 0 to steps x step percent. */
    private static OptionInstance<Integer> percent(String key, int step, int steps, int value, java.util.function.IntConsumer set) {
        String name = "countryroads.config." + key;
        return new OptionInstance<>(name, OptionInstance.cachedConstantTooltip(Component.translatable(name + ".tip")),
            (caption, v) -> Component.translatable("countryroads.config.percent", Component.translatable(name), v * step), new OptionInstance.IntRange(0, steps),
            Math.clamp(Math.round(value / (float) step), 0, steps), v -> set.accept(v * step));
    }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }
}
