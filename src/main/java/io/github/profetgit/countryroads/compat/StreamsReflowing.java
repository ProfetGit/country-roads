package io.github.profetgit.countryroads.compat;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.paint.Canvas;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Streams Reflowing (mod id {@code streamsreflowing}) carves streams and lakes into the land before our FEATURES paint,
 * where the planner cannot see them (it reads the noise column). The painter asks here whether a column holds such
 * water, so a ground road decks over it and its banks leave it alone instead of filling the channel. Black-box on
 * purpose: the mod's licence forbids reading its code, so this only compares the world's blocks with the noise column
 * (water the noise column does not have, or above its water level). Off without the mod: worlds without it paint
 * exactly as before. {@code -Dcountryroads.streamCompat=false} turns it off for tests.
 */
public final class StreamsReflowing {
    private StreamsReflowing() {}

    public static final int NONE = Integer.MIN_VALUE;

    public static final boolean ON = !"false".equals(System.getProperty("countryroads.streamCompat"))
        && CountryRoads.modLoaded.test("streamsreflowing");

    /**
     * Y of the topmost water in this column between lo and hi when it is a stream's (water the noise column does not
     * have there), else NONE. Air and plants above the water are skipped; any other block ends the search.
     */
    public static int water(Canvas canvas, BlockPos.MutableBlockPos pos, int x, int z, int lo, int hi) {
        if (!ON) return NONE;
        for (int y = hi; y >= lo; y--) {
            var s = canvas.get(pos.set(x, y, z));
            if (s.isAir()) continue;
            if (isWater(s.getFluidState())) return unplanned(canvas, x, y, z) ? y : NONE;
            if (s.canBeReplaced()) continue;
            return NONE;
        }
        return NONE;
    }

    /** Y of the topmost water in this column between lo and hi, the noise column's own included, else NONE. */
    public static int anyWater(Canvas canvas, BlockPos.MutableBlockPos pos, int x, int z, int lo, int hi) {
        if (!ON) return NONE;
        for (int y = hi; y >= lo; y--) {
            var s = canvas.get(pos.set(x, y, z));
            if (s.isAir()) continue;
            if (isWater(s.getFluidState())) return y;
            if (s.canBeReplaced()) continue;
            return NONE;
        }
        return NONE;
    }

    /** Dev trace: why a column is or is not stream water. */
    public static String describe(Canvas canvas, int x, int z) {
        ServerLevel level = canvas.level();
        RoadNetwork n = level == null ? null : RoadNetwork.get(level);
        return "on=" + ON + " level=" + (level != null) + " network=" + (n != null)
            + (n == null ? "" : " sample=" + n.terrain.sample(x, z));
    }

    private static boolean isWater(FluidState f) {
        return f.is(Fluids.WATER) || f.is(Fluids.FLOWING_WATER);
    }

    private static boolean unplanned(Canvas canvas, int x, int y, int z) {
        ServerLevel level = canvas.level();
        RoadNetwork n = level == null ? null : RoadNetwork.get(level);
        if (n == null) return false;
        TerrainSampler.Sample s = n.terrain.sample(x, z);
        return !s.wet() || y > s.water();
    }
}
