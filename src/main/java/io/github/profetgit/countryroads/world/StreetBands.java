package io.github.profetgit.countryroads.world;

import io.github.profetgit.countryroads.mixin.StructureTemplateAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** World columns of a village's street surface, read off the street and terminator templates (not the piece boxes). */
public final class StreetBands {
    public static final StreetBands EMPTY = new StreetBands(new LongOpenHashSet(), new LongOpenHashSet());

    private static final Block[] SURFACE = {Blocks.DIRT_PATH, Blocks.SMOOTH_SANDSTONE, Blocks.GRAVEL, Blocks.COARSE_DIRT};

    private final LongOpenHashSet cols;
    /** Columns laid in vanilla street blocks; the rest came from a modded street's placeholder surface (dock only). */
    private final LongOpenHashSet paint;

    private StreetBands(LongOpenHashSet cols, LongOpenHashSet paint) {
        this.cols = cols;
        this.paint = paint;
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public boolean contains(int x, int z) {
        return cols.contains(pack(x, z));
    }

    /** A street column {@link io.github.profetgit.countryroads.paint.StreetPainter} may repaint (modded streets keep their own look). */
    public boolean paints(int x, int z) {
        return paint.contains(pack(x, z));
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

    /**
     * Pieces are the village's street and terminator pieces (already filtered by the caller). A template laid in vanilla
     * street blocks gives those columns; one without any (Towns and Towers, Repurposed Structures, Dungeons and Taverns
     * lay a terracotta placeholder their processors swap for cobble, gravel or grass) gives the columns whose top block is
     * the template's most common top block, for docking only.
     */
    public static StreetBands of(StructureTemplateManager templates, List<PoolElementStructurePiece> pieces) {
        LongOpenHashSet cols = new LongOpenHashSet();
        LongOpenHashSet paint = new LongOpenHashSet();
        for (PoolElementStructurePiece pe : pieces) {
            if (!(pe.getElement() instanceof SinglePoolElement single)) continue;
            StructureTemplate template = templates.get(single.getTemplateLocation()).orElse(null);
            if (template == null) continue;
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pe.getRotation());
            int before = paint.size();
            for (Block b : SURFACE)
                for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(pe.getPosition(), settings, b))
                    paint.add(pack(info.pos().getX(), info.pos().getZ()));
            if (paint.size() > before) continue;
            List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).countryroads$palettes();
            if (palettes.isEmpty()) continue;
            Long2ObjectOpenHashMap<StructureTemplate.StructureBlockInfo> top = new Long2ObjectOpenHashMap<>();
            for (StructureTemplate.StructureBlockInfo info : palettes.getFirst().blocks()) {
                BlockState s = info.state();
                if (s.isAir() || s.is(Blocks.STRUCTURE_VOID) || s.is(Blocks.JIGSAW)) continue;
                long k = pack(info.pos().getX(), info.pos().getZ());
                StructureTemplate.StructureBlockInfo was = top.get(k);
                if (was == null || was.pos().getY() < info.pos().getY()) top.put(k, info);
            }
            Object2IntOpenHashMap<Block> counts = new Object2IntOpenHashMap<>();
            for (StructureTemplate.StructureBlockInfo info : top.values()) counts.addTo(info.state().getBlock(), 1);
            Block main = null;
            for (var e : counts.object2IntEntrySet())
                if (main == null || e.getIntValue() > counts.getInt(main)) main = e.getKey();
            for (StructureTemplate.StructureBlockInfo info : top.values()) {
                if (info.state().getBlock() != main) continue;
                BlockPos p = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(pe.getPosition());
                cols.add(pack(p.getX(), p.getZ()));
            }
        }
        cols.addAll(paint);
        return new StreetBands(cols, paint);
    }
}
