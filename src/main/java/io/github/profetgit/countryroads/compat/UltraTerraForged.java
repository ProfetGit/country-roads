package io.github.profetgit.countryroads.compat;

import io.github.profetgit.countryroads.CountryRoads;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * UltraTerraForged (mod id {@code ultraterraforged}, MIT) fills a noise column from its own 2D heightmap: one
 * {@code getBaseColumn} evaluates that heightmap at every 4-block corner of every 8-block layer (about 3.4 ms per
 * column, against 0.4 ms on vanilla), which makes the router's thousands of samples stall world creation. One
 * heightmap lookup per column gives the same surface. Reflection only: its classes are not on our compile path, and
 * any mismatch ({@link #of} returns null) leaves the caller on the ordinary noise-column path. Off without the mod:
 * worlds without it sample exactly as before. {@code -Dcountryroads.utfCompat=false} turns it off for tests.
 */
public final class UltraTerraForged {
    private static final boolean ENABLED = !"false".equals(System.getProperty("countryroads.utfCompat"));

    private final Object lookup;
    private final Object levels;
    private final Method apply, scale, reset;
    private final Field height, waterLevel, waterY;
    private final java.lang.reflect.Constructor<?> newCell;
    private final ThreadLocal<Object> cell;

    /** ground: top solid block, water: top fluid block (ground when dry), both in blocks. */
    public record Column(int ground, int water) {}

    private UltraTerraForged(Object lookup, Object levels, Method apply, Method scale, Method reset, Field height, Field waterLevel, Field waterY,
                             java.lang.reflect.Constructor<?> newCell) {
        this.lookup = lookup;
        this.levels = levels;
        this.apply = apply;
        this.scale = scale;
        this.reset = reset;
        this.height = height;
        this.waterLevel = waterLevel;
        this.waterY = waterY;
        this.newCell = newCell;
        this.cell = ThreadLocal.withInitial(() -> {
            try {
                return newCell.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** The heightmap of this world's generator, or null when the world is not UltraTerraForged's (or the mod's API differs). */
    public static UltraTerraForged of(RandomState randomState) {
        if (!ENABLED || !CountryRoads.modLoaded.test("ultraterraforged")) return null;
        try {
            Class<?> state = Class.forName("com.pandaismyname1.ultraterraforged.world.worldgen.UTFRandomState", false, randomState.getClass().getClassLoader());
            if (!state.isInstance(randomState)) return null;
            Object ctx = state.getMethod("generatorContext").invoke(randomState);
            if (ctx == null) return null;
            Class<?> ctxClass = ctx.getClass();
            Object lookup = ctxClass.getField("lookup").get(ctx);
            Object levels = ctxClass.getField("levels").get(ctx);
            Class<?> cellClass = Class.forName("com.pandaismyname1.ultraterraforged.world.worldgen.cell.Cell", true, ctxClass.getClassLoader());
            Method apply = lookup.getClass().getMethod("apply", cellClass, int.class, int.class);
            Method scale = levels.getClass().getMethod("scale", float.class);
            return new UltraTerraForged(lookup, levels, apply, scale, cellClass.getMethod("reset"), cellClass.getField("height"), cellClass.getField("waterLevel"),
                levels.getClass().getField("waterY"), cellClass.getConstructor());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CountryRoads.LOG.warn("UltraTerraForged detected but its heightmap is not reachable ({}); using the noise column", e.toString());
            return null;
        }
    }

    /** The column at (x, z) from one heightmap lookup. */
    public Column at(int x, int z) {
        try {
            Object c = cell.get();
            reset.invoke(c);
            apply.invoke(lookup, c, x, z);
            int ground = (Integer) scale.invoke(levels, height.getFloat(c));
            int sea = waterY.getInt(levels);
            int water = ground;
            if (ground < sea) water = sea;
            float w = waterLevel.getFloat(c);
            if (w > 0.0F) {
                int raised = (Integer) scale.invoke(levels, w);
                if (raised > sea && raised > ground) water = raised;
            }
            return new Column(ground, water);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
