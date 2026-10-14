package io.github.profetgit.countryroads.paint;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Lays the roadside props of one chunk (EdgePlan.PROP decor) on the real ground. A prop is rejected whole, before a
 * block is written, when its footprint meets the road, water, a tree, steep ground or a built block; otherwise low
 * columns are filled with dirt up to the floor, plants in the way are cleared and the template is written rotated.
 */
public final class PropPainter {
    private PropPainter() {}

    /** Dev: why props were rejected (by prop and reason), how many were laid, and the reason per anchor column. */
    public static final ConcurrentHashMap<String, AtomicInteger> REJECTS = new ConcurrentHashMap<>();
    public static final AtomicInteger LAID = new AtomicInteger();
    private static final ConcurrentHashMap<Long, String> OUTCOME = new ConcurrentHashMap<>();

    public static String outcome(int x, int z) {
        return OUTCOME.get(BlockPos.asLong(x, 0, z));
    }

    private static final float ROAD = -0.6f;

    /** Columns of the chunk's props (their whole footprint plus a one-block ring): nothing else grows through them. */
    public static boolean[] footprint(ChunkMask m) {
        boolean[] cols = null;
        for (EdgePlan.Decor d : m.decor) {
            if (d.type() != EdgePlan.PROP) continue;
            Props.Prop pr = Props.byName(d.front1());
            if (pr == null) continue;
            if (cols == null) cols = new boolean[256];
            Rotation r = Props.rotation(d.toRoad());
            for (int x = pr.minX - 1; x <= pr.maxX + 1; x++)
                for (int z = pr.minZ - 1; z <= pr.maxZ + 1; z++) {
                    int wx = d.x() + Props.rx(r, x, z), wz = d.z() + Props.rz(r, x, z);
                    if (wx >> 4 == m.cx && wz >> 4 == m.cz) cols[ChunkMask.idx(wx & 15, wz & 15)] = true;
                }
        }
        return cols;
    }

    private static void note(Props.Prop pr, EdgePlan.Decor d, String result) {
        OUTCOME.put(BlockPos.asLong(d.x(), 0, d.z()), result);
        if (OUTCOME.size() > 20000) OUTCOME.clear();
        if (result.equals("placed")) LAID.incrementAndGet();
        else {
            REJECTS.computeIfAbsent(pr.name + ":" + result, k -> new AtomicInteger()).incrementAndGet();
            CountryRoads.LOG.debug("CR-PROP rejected {} at {},{}: {}", pr.name, d.x(), d.z(), result);
        }
    }

    /** Lays the prop; returns null when laid, else the reason. */
    public static String paint(Canvas cv, ChunkMask m, EdgePlan.Decor d) {
        Props.Prop pr = Props.byName(d.front1());
        if (pr == null) return "unknown";
        String why;
        try {
            why = lay(cv, m, d, pr);
        } catch (RuntimeException e) {
            CountryRoads.LOG.error("Country Roads: prop {} failed at {},{}", pr.name, d.x(), d.z(), e);
            why = "error";
        }
        note(pr, d, why == null ? "placed" : why);
        return why;
    }

    private static int groundAt(Canvas cv, BlockPos.MutableBlockPos pos, int x, int z, int near) {
        int hi = Math.min(cv.maxY() - 1, near + 10), lo = Math.max(cv.minY(), near - 14);
        for (int y = hi; y >= lo; y--) if (PaintRules.terrain(cv.get(pos.set(x, y, z)))) return y;
        return lo;
    }

    /** Air, or a plant or snow layer the prop may take the place of: flowers, bushes and saplings are not "replaceable" in vanilla. */
    private static boolean clearable(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return false;
        return s.canBeReplaced() || s.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock;
    }

    private static boolean obstruction(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static String lay(Canvas cv, ChunkMask m, EdgePlan.Decor d, Props.Prop pr) {
        Direction dir = d.toRoad();
        Rotation rot = Props.rotation(dir);
        int ax = d.x(), az = d.z();
        if (ax >> 4 != m.cx || az >> 4 != m.cz || !Props.fitsChunk(pr, ax, az, dir)) return "chunk";
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = (int) Math.floor(d.y());
        int g0 = groundAt(cv, pos, ax, az, top);
        BlockState soilNow = cv.get(pos.set(ax, g0, az));
        if (!PaintRules.terrain(soilNow)) return "noground";
        int floor = g0 + 1;
        BlockState soil = PaintRules.surfaceOf(soilNow);
        int wood = Palette.wood(cv.biome(ax, floor, az));
        BlockState[] st = pr.states(wood);

        // column kinds: 1 = only ground cells (a trodden trail), 2 = only plants, 3 = a solid cell stands on it
        byte[] kind = new byte[256];
        int[] ground = new int[256];
        int[] cx = new int[pr.cells.length], cz = new int[pr.cells.length];
        for (int k = 0; k < pr.cells.length; k++) {
            Props.Cell c = pr.cells[k];
            int wx = ax + Props.rx(rot, c.x(), c.z()), wz = az + Props.rz(rot, c.x(), c.z());
            cx[k] = wx;
            cz[k] = wz;
            int i = ChunkMask.idx(wx & 15, wz & 15);
            int kd = c.y() < 0 ? 1 : st[c.pal()].isAir() || Props.isPlant(st[c.pal()]) ? 2 : 3;
            kind[i] = (byte) Math.max(kind[i], kd);
        }
        boolean[] skip = new boolean[256];
        int height = pr.height;
        // the floor is one above the highest ground under a solid cell (a cut bank rises away from the road): lower columns are filled
        for (int i = 0; i < 256; i++) {
            if (kind[i] == 0) continue;
            int wx = (m.cx << 4) + (i & 15), wz = (m.cz << 4) + (i >> 4);
            if (m.in[i] >= ROAD) {
                if (kind[i] == 3) return "road";
                skip[i] = true;
                continue;
            }
            ground[i] = groundAt(cv, pos, wx, wz, g0);
            if (kind[i] == 3) floor = Math.max(floor, ground[i] + 1);
        }
        for (int i = 0; i < 256; i++) {
            if (kind[i] != 3 || skip[i]) continue;
            int wx = (m.cx << 4) + (i & 15), wz = (m.cz << 4) + (i >> 4), g = ground[i];
            if (!cv.get(pos.set(wx, g + 1, wz)).getFluidState().isEmpty() || !cv.get(pos.set(wx, floor, wz)).getFluidState().isEmpty()
                || !PaintRules.terrain(cv.get(pos.set(wx, g, wz)))) return "water";
            if (g > floor - 1) return "high";
            if (g < floor - 3) return "low";
            for (int y = floor; y <= floor + height; y++) {
                BlockState here = cv.get(pos.set(wx, y, wz));
                if (obstruction(here)) return "tree";
            }
        }
        for (int k = 0; k < pr.cells.length; k++) {
            Props.Cell c = pr.cells[k];
            BlockState s = st[c.pal()];
            if (c.y() < 0 || s.isAir() || Props.isPlant(s)) continue;
            BlockState here = cv.get(pos.set(cx[k], floor + c.y(), cz[k]));
            if (!clearable(here)) return "blocked";
        }

        // fill low columns up to the floor, clear plants in the way
        int[] top2 = new int[256];
        for (int i = 0; i < 256; i++) {
            if (kind[i] == 0 || skip[i]) continue;
            int wx = (m.cx << 4) + (i & 15), wz = (m.cz << 4) + (i >> 4);
            int g = ground[i];
            if (kind[i] == 3 && g < floor - 1) {
                for (int y = g + 1; y <= floor - 2; y++) cv.set(pos.set(wx, y, wz), Blocks.DIRT.defaultBlockState());
                cv.set(pos.set(wx, floor - 1, wz), soil);
                g = floor - 1;
            }
            top2[i] = g;
            int yTo = kind[i] == 1 ? g + 1 : floor + height;
            for (int y = g + 1; y <= yTo; y++) {
                BlockState here = cv.get(pos.set(wx, y, wz));
                if (here.isAir() || !clearable(here)) continue;
                cv.set(pos.set(wx, y, wz), Blocks.AIR.defaultBlockState());
                // a tall plant's upper half goes with it (a ground-only column clears just the floor cell)
                if (Palette.upperHalf(here) != null && cv.get(pos.set(wx, y + 1, wz)).is(here.getBlock()))
                    cv.set(pos.set(wx, y + 1, wz), Blocks.AIR.defaultBlockState());
            }
        }

        for (int k = 0; k < pr.cells.length; k++) {
            Props.Cell c = pr.cells[k];
            BlockState s = st[c.pal()];
            if (s.isAir()) continue;
            int wx = cx[k], wz = cz[k], i = ChunkMask.idx(wx & 15, wz & 15);
            // ground cells follow their own column's ground (y -1 = its top block): a column a step lower keeps the
            // stash's loose cobble on top and the barrel under it, never an open pit
            int wy = c.y() < 0 ? top2[i] + 1 + c.y() : floor + c.y();
            if (skip[i]) continue;
            BlockState rotated = s.rotate(rot);
            if (c.y() < 0) {
                if (!PaintRules.terrain(cv.get(pos.set(wx, wy, wz)))) continue;
            } else {
                BlockState here = cv.get(pos.set(wx, wy, wz));
                if (!clearable(here)) continue;
                if (Props.isPlant(s) && !Palette.plantable(rotated, cv.get(pos.set(wx, wy - 1, wz)))) continue;
            }
            cv.set(pos.set(wx, wy, wz), rotated);
        }

        for (Props.Loot l : pr.loot) {
            int wx = ax + Props.rx(rot, l.x(), l.z()), wz = az + Props.rz(rot, l.x(), l.z());
            int wy = l.y() < 0 ? top2[ChunkMask.idx(wx & 15, wz & 15)] + 1 + l.y() : floor + l.y();    // as its block was laid
            BlockPos at = new BlockPos(wx, wy, wz);
            BlockState here = cv.get(at);
            if (!(here.getBlock() instanceof EntityBlock eb)) continue;
            BlockEntity be = eb.newBlockEntity(at, here);
            if (be instanceof RandomizableContainer rc) {
                rc.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(l.table())), PaintRules.mixPos(wx, wy, wz));
                cv.blockEntity(be);
            }
        }

        ServerLevel level = cv.level();
        for (Props.Ent e : pr.entities) {
            if (!e.type().equals("cushion")) continue;
            if (level == null) return null;
            int wx = ax + Props.rx(rot, e.x(), e.z()), wz = az + Props.rz(rot, e.x(), e.z());
            if (skip[ChunkMask.idx(wx & 15, wz & 15)]) continue;
            if (cv.get(pos.set(wx, floor + (int) Math.floor(e.y()), wz)).isAir()) continue;
            Cushion cushion = EntityTypes.CUSHION.create(level, EntitySpawnReason.STRUCTURE);
            if (cushion == null) continue;
            cushion.setColor(e.color());
            cushion.snapTo(wx + 0.5, floor + e.y(), wz + 0.5, rot.rotate(e.facing()).toYRot(), 0f);
            cv.addEntity(cushion);
        }
        return null;
    }
}
