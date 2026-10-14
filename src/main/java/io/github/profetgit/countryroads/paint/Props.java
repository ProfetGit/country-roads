package io.github.profetgit.countryroads.paint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.profetgit.countryroads.CountryRoads;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The roadside prop templates (data/countryroads/props/*.json, written by dev/props/export.py): a cell list in a local
 * frame (x along the road, +z toward the road for front "road", away from it for "away", y 0 = the floor a player
 * stands on, y below 0 replaces ground), a palette with "@main" standing for the biome's wood, containers that get a
 * loot table and cushions.
 */
public final class Props {
    private Props() {}

    public static final int LOST_CART = 0, MILESTONE = 1, STASH = 2, SHRINE = 3, BENCH = 4, HAY_CART = 5, HAY_BALES = 6;
    public static final String[] NAMES = {"lost_cart", "milestone", "milestone_stash", "wayside_shrine", "bench", "hay_cart", "hay_bales"};
    static final String[] WOOD_NAMES = {"spruce", "oak", "mangrove", "dark_oak", "jungle"};

    public record Cell(int x, int y, int z, int pal) {}

    public record Loot(int x, int y, int z, String table) {}

    public record Ent(String type, int x, float y, int z, DyeColor color, Direction facing) {}

    public static final class Prop {
        public final int id;
        public final String name;
        public final boolean away;
        public final String[] palette;
        public final Cell[] cells;
        public final Loot[] loot;
        public final Ent[] entities;
        /** Extents of all cells and entities (the footprint), and of the cells at y >= 0. */
        public final int minX, maxX, minZ, maxZ, aboveMinX, aboveMaxX, aboveMinZ, aboveMaxZ, height;
        /** Distance (blocks) from the anchor to the road-nearest above-ground cell along the road axis. */
        public final int zNear;
        private final ConcurrentHashMap<Integer, BlockState[]> states = new ConcurrentHashMap<>();

        Prop(int id, String name, boolean away, String[] palette, Cell[] cells, Loot[] loot, Ent[] entities) {
            this.id = id;
            this.name = name;
            this.away = away;
            this.palette = palette;
            this.cells = cells;
            this.loot = loot;
            this.entities = entities;
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            int ax0 = Integer.MAX_VALUE, ax1 = Integer.MIN_VALUE, az0 = Integer.MAX_VALUE, az1 = Integer.MIN_VALUE, h = 0;
            for (Cell c : cells) {
                x0 = Math.min(x0, c.x());
                x1 = Math.max(x1, c.x());
                z0 = Math.min(z0, c.z());
                z1 = Math.max(z1, c.z());
                if (c.y() >= 0) {
                    ax0 = Math.min(ax0, c.x());
                    ax1 = Math.max(ax1, c.x());
                    az0 = Math.min(az0, c.z());
                    az1 = Math.max(az1, c.z());
                    h = Math.max(h, c.y() + 1);
                }
            }
            for (Ent e : entities) {
                x0 = Math.min(x0, e.x());
                x1 = Math.max(x1, e.x());
                z0 = Math.min(z0, e.z());
                z1 = Math.max(z1, e.z());
                ax0 = Math.min(ax0, e.x());
                ax1 = Math.max(ax1, e.x());
                az0 = Math.min(az0, e.z());
                az1 = Math.max(az1, e.z());
                h = Math.max(h, (int) Math.ceil(e.y()) + 1);
            }
            if (ax0 == Integer.MAX_VALUE) {
                ax0 = ax1 = az0 = az1 = 0;
            }
            minX = x0;
            maxX = x1;
            minZ = z0;
            maxZ = z1;
            aboveMinX = ax0;
            aboveMaxX = ax1;
            aboveMinZ = az0;
            aboveMaxZ = az1;
            height = h;
            zNear = away ? -az0 : az1;
        }

        public BlockState[] states(int wood) {
            return states.computeIfAbsent(wood, w -> {
                String name = WOOD_NAMES[Math.clamp(w, 0, WOOD_NAMES.length - 1)];
                BlockState[] out = new BlockState[palette.length];
                for (int i = 0; i < out.length; i++) out[i] = parse(palette[i].replace("@main", name));
                return out;
            });
        }

        public boolean isPlant(int wood, Cell c) {
            return Props.isPlant(states(wood)[c.pal()]);
        }
    }

    private static final Prop[] ALL = new Prop[NAMES.length];
    public static final boolean OK;

    static {
        boolean ok = true;
        for (int i = 0; i < NAMES.length; i++) {
            try {
                ALL[i] = load(i, NAMES[i]);
            } catch (Exception e) {
                ok = false;
                CountryRoads.LOG.error("Country Roads: the prop template {} could not be read", NAMES[i], e);
            }
        }
        OK = ok;
    }

    public static Prop get(int id) {
        return ALL[id];
    }

    public static Prop byName(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return ALL[i];
        return null;
    }

    private static Prop load(int id, String name) throws Exception {
        try (var in = Props.class.getResourceAsStream("/data/countryroads/props/" + name + ".json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray pal = o.getAsJsonArray("palette");
            String[] palette = new String[pal.size()];
            for (int i = 0; i < palette.length; i++) palette[i] = pal.get(i).getAsString();
            List<Cell> cells = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("blocks")) {
                JsonArray a = e.getAsJsonArray();
                cells.add(new Cell(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()));
            }
            cells.sort(Comparator.comparingInt(Cell::y));
            List<Loot> loot = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("loot")) {
                JsonObject l = e.getAsJsonObject();
                loot.add(new Loot(l.get("x").getAsInt(), l.get("y").getAsInt(), l.get("z").getAsInt(), l.get("table").getAsString()));
            }
            List<Ent> ents = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("entities")) {
                JsonObject l = e.getAsJsonObject();
                DyeColor color = DyeColor.byName(l.get("color").getAsString(), DyeColor.WHITE);
                Direction facing = Direction.byName(l.get("facing").getAsString());
                ents.add(new Ent(l.get("type").getAsString(), l.get("x").getAsInt(), l.get("y").getAsFloat(), l.get("z").getAsInt(), color,
                    facing == null || facing.getAxis().isVertical() ? Direction.SOUTH : facing));
            }
            return new Prop(id, name, "away".equals(o.get("front").getAsString()), palette, cells.toArray(new Cell[0]), loot.toArray(new Loot[0]),
                ents.toArray(new Ent[0]));
        }
    }

    /** "minecraft:block[prop=value,...]" as a state; an unknown block or property is logged and becomes air / is ignored. */
    static BlockState parse(String s) {
        String id = s, props = "";
        int br = s.indexOf('[');
        if (br >= 0) {
            id = s.substring(0, br);
            props = s.substring(br + 1, s.length() - 1);
        }
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            CountryRoads.LOG.error("Country Roads: unknown block {} in a prop template", s);
            return Blocks.AIR.defaultBlockState();
        }
        Block block = BuiltInRegistries.BLOCK.getValue(key);
        BlockState state = block.defaultBlockState();
        StateDefinition<Block, BlockState> def = block.getStateDefinition();
        if (!props.isEmpty())
            for (String kv : props.split(",")) {
                String[] p = kv.split("=", 2);
                Property<?> prop = def.getProperty(p[0]);
                if (prop == null || p.length < 2) {
                    CountryRoads.LOG.error("Country Roads: unknown property {} in a prop template ({})", kv, s);
                    continue;
                }
                state = with(state, prop, p[1]);
            }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String v) {
        return p.getValue(v).map(val -> state.setValue(p, val)).orElseGet(() -> {
            CountryRoads.LOG.error("Country Roads: bad value {} for {} in a prop template", v, p.getName());
            return state;
        });
    }

    /** Plants a template may carry: placed only on soil that can hold them. */
    public static boolean isPlant(BlockState s) {
        return s.is(BlockTags.SMALL_FLOWERS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN)
            || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.BUSH);
    }

    /** The rotation that turns local +z (south) to run toward dir. */
    public static Rotation rotation(Direction dir) {
        return switch (dir) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Local x rotated, as an offset from the anchor. */
    public static int rx(Rotation r, int x, int z) {
        return switch (r) {
            case CLOCKWISE_90 -> -z;
            case CLOCKWISE_180 -> -x;
            case COUNTERCLOCKWISE_90 -> z;
            default -> x;
        };
    }

    public static int rz(Rotation r, int x, int z) {
        return switch (r) {
            case CLOCKWISE_90 -> x;
            case CLOCKWISE_180 -> -z;
            case COUNTERCLOCKWISE_90 -> -x;
            default -> z;
        };
    }

    /** Whether the prop's whole footprint (every cell and entity) stays inside the anchor's chunk. */
    public static boolean fitsChunk(Prop p, int ax, int az, Direction dir) {
        Rotation r = rotation(dir);
        int cx = ax >> 4, cz = az >> 4;
        for (int i = 0; i < 4; i++) {
            int lx = (i & 1) == 0 ? p.minX : p.maxX, lz = (i & 2) == 0 ? p.minZ : p.maxZ;
            int wx = ax + rx(r, lx, lz), wz = az + rz(r, lx, lz);
            if (wx >> 4 != cx || wz >> 4 != cz) return false;
        }
        return true;
    }
}
