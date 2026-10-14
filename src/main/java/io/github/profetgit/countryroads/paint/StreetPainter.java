package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.gen.GenContext;
import io.github.profetgit.countryroads.world.RoadColumns;
import io.github.profetgit.countryroads.world.StreetBands;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Village streets in the road look: the street surface blocks of a village (columns from {@link StreetBands}) are
 * swapped for the biome's road mix, edge columns frayed, with the same noise as the roads so a road arriving at a street
 * continues it without a seam. Same-height swaps only: nothing is cut, filled or stepped, and only blocks that are
 * still vanilla street blocks change (a column someone built over, or planks over water, stays).
 */
public final class StreetPainter {
    public static final boolean ENABLED = !"false".equals(System.getProperty("countryroads.villageStreets"));

    private StreetPainter() {}

    private static boolean isStreet(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT);
    }

    /** Repaints this chunk's street columns; returns how many. */
    public static int paint(Canvas canvas, List<StreetBands> streets, int cx, int cz, RoadColumns cols) {
        if (streets.isEmpty()) return 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int painted = 0;
        GenContext.bypass(true);
        try {
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    StreetBands band = null;
                    for (StreetBands b : streets)
                        if (b.contains(x, z)) {
                            band = b;
                            break;
                        }
                    if (band == null) continue;
                    int y = canvas.surface(x, z) + 2;
                    int low = Math.max(canvas.minY() + 1, y - 14);
                    BlockState here = null;
                    for (; y >= low; y--) {
                        BlockState s = canvas.get(pos.set(x, y, z));
                        if (s.isAir() || !s.isSolid() && !isStreet(s)) continue;
                        here = s;
                        break;
                    }
                    if (here == null || !isStreet(here)) continue;
                    Palette.Surface pal = Palette.surface(canvas.biome(x, y, z));
                    BlockState next = Palette.surface(pal, band.edge(x, z) == 0 ? 0.3f : 1f, RoadPainter.hash(x, z), here, true);
                    if (next.getBlock() != here.getBlock()) canvas.set(pos.set(x, y, z), next);
                    if (cols != null) cols.set(lx, lz, true);
                    painted++;
                }
        } finally {
            GenContext.bypass(false);
        }
        return painted;
    }
}
