package io.github.profetgit.countryroads;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Settings in config/countryroads.json (on a server, the server's config folder). Gameplay settings apply at once.
 * The {@link Gen} section is what a new world is generated with: the first time a world loads it is copied into the
 * world's own countryroads.json and that copy is used from then on, because roads are planned across chunks and a
 * change halfway through a world would cut them off at the old chunks.
 */
public final class Config {
    /** The road generation revision new worlds get (see {@link Gen#revision}). */
    public static final int REVISION = 2;

    /** World generation (fixed per world). */
    public static final class Gen {
        /** Village streets repainted in the road look. Off: vanilla streets, roads still dock onto them. */
        public boolean villageStreets = true;
        /** Signposts at junctions and village ends, lamp posts near villages. */
        public boolean signposts = true;
        /** Abandoned camps beside some long roads. */
        public boolean waysideCamps = true;
        /** Milestones, lost carts, shrines, benches and hay carts, in percent of the normal rate (0 to 300). */
        public int roadsideProps = 100;
        /** Wheat fields beside plains roads, in percent of the normal rate (0 to 300). */
        public int wheatFields = 100;
        /**
         * Road generation revision the world was made with; a world's file without it is 0 (made before 1.0.0-beta.5).
         * 0: roads may run through outposts, ruined portals and other structures. 1: they go around them. 2: a road
         * joining another meets its height where the two already overlap, and a sharp bend at a bridge end is evened
         * (1.5-block steps). An existing world keeps its revision: newer routing there would not meet the roads already
         * generated.
         */
        public int revision = REVISION;

        /** Revision of a world file that has no "revision" field: made before the field existed. */
        static Gen read(String json) {
            Gen g = GSON.fromJson(json, Gen.class);
            if (g != null && !com.google.gson.JsonParser.parseString(json).getAsJsonObject().has("revision")) g.revision = 0;
            return g;
        }

        Gen copy() {
            return GSON.fromJson(GSON.toJson(this), Gen.class);
        }

        void clamp() {
            roadsideProps = Math.clamp(roadsideProps, 0, 300);
            wheatFields = Math.clamp(wheatFields, 0, 300);
        }
    }

    /** Defaults for new worlds. */
    public Gen newWorlds = new Gen();
    /** Chunks generated before the mod was installed get their road the first time they load (never lived-in ones). */
    public boolean roadsInOldChunks = true;
    /** The Surveyor's Stake builds paths from houses to the nearest road. */
    public boolean housePaths = true;
    /** Walking speed bonus on roads, percent (0 to 50). */
    public int playerSpeed = 20;
    /** Speed bonus for horses, donkeys, mules and camels on roads, percent (0 to 50). */
    public int mountSpeed = 30;
    /** No monsters spawn on the road surface. */
    public boolean noMonstersOnRoads = true;
    /** Roads drawn as light lines on maps. */
    public boolean roadsOnMaps = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String NAME = "countryroads.json";
    private static Config current;
    private static Path file;
    /** The loaded world's generation settings; the global defaults while no world is loaded. */
    private static volatile Gen world;

    public static synchronized Config get() {
        if (current == null) load();
        return current;
    }

    public static Gen world() {
        Gen g = world;
        return g != null ? g : get().newWorlds;
    }

    public static synchronized void load() {
        file = CountryRoads.configDir.resolve(NAME);
        Config c = null;
        try {
            if (Files.isRegularFile(file)) c = GSON.fromJson(Files.readString(file), Config.class);
        } catch (Exception e) {
            CountryRoads.LOG.warn("Country Roads: cannot read {}, using defaults ({})", file, e.toString());
        }
        current = c == null ? new Config() : c;
        if (current.newWorlds == null) current.newWorlds = new Gen();
        current.newWorlds.revision = REVISION;
        current.clamp();
        save();
    }

    private void clamp() {
        newWorlds.clamp();
        playerSpeed = Math.clamp(playerSpeed, 0, 50);
        mountSpeed = Math.clamp(mountSpeed, 0, 50);
    }

    public static synchronized void save() {
        if (current == null || file == null) return;
        current.clamp();
        write(file, current);
    }

    /** Server starting: read the world's own generation settings, or give a new world the current defaults. */
    public static void worldStarting(MinecraftServer server) {
        Path f = server.getWorldPath(LevelResource.ROOT).resolve(NAME);
        Gen g = null;
        try {
            if (Files.isRegularFile(f)) g = Gen.read(Files.readString(f));
        } catch (Exception e) {
            CountryRoads.LOG.warn("Country Roads: cannot read {}, using the defaults for new worlds ({})", f, e.toString());
        }
        if (g == null) {
            g = get().newWorlds.copy();
            write(f, g);
        }
        g.clamp();
        world = g;
    }

    public static void worldStopped() {
        world = null;
    }

    private static void write(Path f, Object o) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o));
        } catch (Exception e) {
            CountryRoads.LOG.warn("Country Roads: cannot write {} ({})", f, e.toString());
        }
    }
}
