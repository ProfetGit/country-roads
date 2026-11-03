package io.github.profetgit.countryroads;

import com.mojang.brigadier.CommandDispatcher;
import io.github.profetgit.countryroads.dev.DevCommands;
import io.github.profetgit.countryroads.path.HousePaths;
import io.github.profetgit.countryroads.path.Stake;
import io.github.profetgit.countryroads.perk.RoadSpeed;
import io.github.profetgit.countryroads.plan.Prefetch;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.retro.Retrofit;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-neutral entry: the loader glue in {@code platform/<loader>} calls these from its own events. */
public final class CountryRoads {
    public static final String ID = "countryroads";
    public static final Logger LOG = LoggerFactory.getLogger("Country Roads");
    public static String loader = "fabric";
    public static Path configDir = Path.of("config");

    /** Puts one object into a registry while the loader has it open. */
    public interface Registrar {
        <B, T extends B> T add(ResourceKey<? extends Registry<B>> registry, ResourceKey<B> key, T value);
    }

    private CountryRoads() {}

    public static void init(String loader, Path configDir) {
        CountryRoads.loader = loader;
        CountryRoads.configDir = configDir;
        Config.load();
        LOG.info("Country Roads loaded ({})", loader);
    }

    public static void register(Registrar r) {
        Stake.register(r);
        HousePaths.register(r);
    }

    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        DevCommands.register(dispatcher);
    }

    public static void serverStarting(MinecraftServer server) {
        Config.worldStarting(server);
    }

    /** End of every server tick, after the levels ticked. */
    public static void serverTick(MinecraftServer server) {
        HousePaths.tick(server);
        DevCommands.tick();
        Retrofit.tick();
        Prefetch.tick(server);
    }

    /** End of a server level's tick. */
    public static void levelTick(ServerLevel level) {
        RoadSpeed.tick(level);
    }

    public static void serverStopping(MinecraftServer server) {
        HousePaths.stop();
    }

    public static void serverStopped(MinecraftServer server) {
        RoadNetwork.clear();
        Config.worldStopped();
    }

    /** After /reload replaced the data packs. */
    public static void dataReloaded(MinecraftServer server) {
        HousePaths.reloaded();
    }
}
