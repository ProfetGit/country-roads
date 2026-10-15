package io.github.profetgit.countryroads.world;

import io.github.profetgit.countryroads.mixin.StructureTemplateAccessor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * A village without streets (Towns and Towers' inn, lighthouse, villager outpost) is reached at a door: from each
 * ground-floor door or gate of its templates, the columns straight out in front of it (x, z, floor y: the block under the
 * door), clear of head-height blocks, fences and walls, until the walk leaves every piece box. Read off the templates, so it is the same in every chunk.
 */
public final class DoorWalks {
    private static final int MAX_WALK = 24;
    private static final boolean TRACE = Boolean.getBoolean("countryroads.traceDoors");

    private DoorWalks() {}

    public static List<List<int[]>> of(StructureTemplateManager templates, List<PoolElementStructurePiece> pieces, List<BoundingBox> boxes) {
        LongOpenHashSet solid = new LongOpenHashSet(), fence = new LongOpenHashSet();
        List<BlockPos> doors = new ArrayList<>();
        List<Direction> facing = new ArrayList<>();
        for (PoolElementStructurePiece pe : pieces) {
            if (!(pe.getElement() instanceof SinglePoolElement single)) continue;
            StructureTemplate template = templates.get(single.getTemplateLocation()).orElse(null);
            if (template == null) continue;
            List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).countryroads$palettes();
            if (palettes.isEmpty()) continue;
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pe.getRotation());
            int lowest = Integer.MAX_VALUE;
            List<StructureTemplate.StructureBlockInfo> own = new ArrayList<>();
            for (StructureTemplate.StructureBlockInfo info : palettes.getFirst().blocks()) {
                BlockState s = info.state();
                BlockPos p = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(pe.getPosition());
                boolean door = s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    || s.getBlock() instanceof FenceGateBlock;
                if (door) {
                    own.add(new StructureTemplate.StructureBlockInfo(p, s.rotate(pe.getRotation()), null));
                    lowest = Math.min(lowest, info.pos().getY());
                } else if (!(s.getBlock() instanceof DoorBlock) && s.isSolid()) {
                    solid.add(p.asLong());
                    if (s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS)) fence.add(p.asLong());
                }
            }
            for (StructureTemplate.StructureBlockInfo d : own)
                if (d.pos().getY() - pe.getPosition().getY() <= lowest + 1) {
                    doors.add(d.pos());
                    facing.add(d.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
                }
        }
        List<List<int[]>> out = new ArrayList<>();
        for (int i = 0; i < doors.size(); i++) {
            BlockPos p = doors.get(i);
            List<int[]> best = null;
            StringBuilder why = new StringBuilder();
            for (Direction dir : new Direction[]{facing.get(i), facing.get(i).getOpposite()}) {
                List<int[]> walk = new ArrayList<>();
                for (int k = 1; k <= MAX_WALK; k++) {
                    int x = p.getX() + dir.getStepX() * k, z = p.getZ() + dir.getStepZ() * k;
                    // a step or slab at the floor is walked over; anything at head height, or a fence or wall, is not
                    if (solid.contains(BlockPos.asLong(x, p.getY() + 1, z)) || fence.contains(BlockPos.asLong(x, p.getY(), z))) {
                        why.append(dir).append(" blocked@").append(k).append(' ');
                        walk = null;
                        break;
                    }
                    walk.add(new int[]{x, z, p.getY() - 1});
                    if (!inside(boxes, x, z)) break;
                    if (k == MAX_WALK) {
                        why.append(dir).append(" long ");
                        walk = null;
                    }
                }
                if (walk != null && !walk.isEmpty() && (best == null || walk.size() < best.size())) best = walk;
            }
            if (best != null) out.add(best);
            if (TRACE) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOOR " + p.toShortString() + " facing " + facing.get(i)
                + (best == null ? " no walk" : " walk " + best.size() + " to " + best.getLast()[0] + "," + best.getLast()[1]) + " " + why);
        }
        return out;
    }

    private static boolean inside(List<BoundingBox> boxes, int x, int z) {
        for (BoundingBox b : boxes)
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) return true;
        return false;
    }
}
