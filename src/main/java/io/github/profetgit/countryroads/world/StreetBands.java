package io.github.profetgit.countryroads.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** World columns of a village's street surface, read off the street and terminator templates (not the piece boxes). */
public final class StreetBands {
    public static final StreetBands EMPTY = new StreetBands(new LongOpenHashSet());

    private static final Block[] SURFACE = {Blocks.DIRT_PATH, Blocks.SMOOTH_SANDSTONE, Blocks.GRAVEL, Blocks.COARSE_DIRT};

    private final LongOpenHashSet cols;

    private StreetBands(LongOpenHashSet cols) {
        this.cols = cols;
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public boolean contains(int x, int z) {
        return cols.contains(pack(x, z));
    }

    public boolean isEmpty() {
        return cols.isEmpty();
    }

    public int size() {
        return cols.size();
    }

    /** 0 when a 4-neighbour is not band (the street's edge), 1 inside, -1 outside the band. */
    public int edge(int x, int z) {
        if (!contains(x, z)) return -1;
        return contains(x + 1, z) && contains(x - 1, z) && contains(x, z + 1) && contains(x, z - 1) ? 1 : 0;
    }

    /** Every band column as {x, z}; for tests and docking. */
    public List<int[]> columns() {
        List<int[]> out = new ArrayList<>(cols.size());
        for (long k : cols) out.add(new int[] {(int) (k >> 32), (int) k});
        return out;
    }

    /** Pieces are the village's street and terminator pieces (already filtered by the caller). */
    public static StreetBands of(StructureTemplateManager templates, List<PoolElementStructurePiece> pieces) {
        LongOpenHashSet cols = new LongOpenHashSet();
        for (PoolElementStructurePiece pe : pieces) {
            if (!(pe.getElement() instanceof SinglePoolElement single)) continue;
            StructureTemplate template = templates.get(single.getTemplateLocation()).orElse(null);
            if (template == null) continue;
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pe.getRotation());
            for (Block b : SURFACE)
                for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(pe.getPosition(), settings, b))
                    cols.add(pack(info.pos().getX(), info.pos().getZ()));
        }
        return new StreetBands(cols);
    }
}
