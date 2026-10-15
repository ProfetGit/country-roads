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
        super(parent, Minecraft.getInstance().options, Component.literal("Country Roads"));
    }

    @Override
    protected void addOptions() {
        Config c = Config.get();
        Config.Gen g = c.newWorlds;
        list.addHeader(Component.literal("Gameplay (servers: config/countryroads.json)"));
        list.addSmall(
            percent("Walking speed on roads", "Speed bonus for players on a road. 20% is about Speed I.", 5, 10, c.playerSpeed, v -> c.playerSpeed = v),
            percent("Riding speed on roads", "Speed bonus for horses, donkeys, mules and camels on a road.", 5, 10, c.mountSpeed, v -> c.mountSpeed = v),
            bool("No monsters on roads", "Monsters do not spawn on the road surface. Caves under a road are not affected.", c.noMonstersOnRoads, v -> c.noMonstersOnRoads = v),
            bool("Roads on maps", "Roads are drawn as light lines on maps.", c.roadsOnMaps, v -> c.roadsOnMaps = v),
            bool("House paths", "The Surveyor's Stake builds a path from your door to the nearest road.", c.housePaths, v -> c.housePaths = v),
            bool("Roads in old chunks", "Chunks generated before Country Roads was installed get their road when they load. Chunks you have lived in are never touched.",
                c.roadsInOldChunks, v -> c.roadsInOldChunks = v));
        list.addHeader(Component.literal("New worlds (each world keeps what it was created with)"));
        list.addSmall(
            bool("Village streets", "Village streets get the same look as the roads. Off: vanilla streets (roads still join them).", g.villageStreets, v -> g.villageStreets = v),
            bool("Signposts and lamps", "Signposts with destinations and distances, and lamp posts near villages.", g.signposts, v -> g.signposts = v),
            bool("Wayside camps", "Abandoned camps beside some long roads.", g.waysideCamps, v -> g.waysideCamps = v),
            percent("Roadside finds", "Milestones, lost carts, shrines, benches and hay carts. 0%: none.", 25, 12, g.roadsideProps, v -> g.roadsideProps = v),
            percent("Wheat fields", "Wheat fields beside roads in plains. 0%: none.", 25, 12, g.wheatFields, v -> g.wheatFields = v));
    }

    private static OptionInstance<Boolean> bool(String name, String tip, boolean value, OptionInstance.ValueUpdateListener<Boolean> set) {
        return OptionInstance.createBoolean(name, OptionInstance.cachedConstantTooltip(Component.literal(tip)), value, set);
    }

    /** A slider from 0 to steps x step percent. */
    private static OptionInstance<Integer> percent(String name, String tip, int step, int steps, int value, java.util.function.IntConsumer set) {
        return new OptionInstance<>(name, OptionInstance.cachedConstantTooltip(Component.literal(tip)),
            (caption, v) -> Component.literal(name + ": " + v * step + "%"), new OptionInstance.IntRange(0, steps),
            Math.clamp(Math.round(value / (float) step), 0, steps), v -> set.accept(v * step));
    }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }
}
