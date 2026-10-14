package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What one house path remembers until it is made permanent: the original blocks (to put back), what it laid, the
 * columns it marked as road, the materials an old build was paid. A stake keeps it in its block entity; a path started
 * from a door or fence gate keeps it in PathRecords under the door's position.
 */
public final class PathData {
    /** The block that stood at pos before the path (state) and what the path put there (laid; null in old saves). */
    public record Entry(BlockPos pos, BlockState state, BlockState laid) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
            BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
            BlockState.CODEC.lenientOptionalFieldOf("laid").forGetter(e -> Optional.ofNullable(e.laid()))).apply(i, (p, s, l) -> new Entry(p, s, l.orElse(null))));
    }

    public static final int NONE = 0, PREVIEW = 1, BUILT = 2;
    public static final int DOOR = 0, WAYPOINT = 1;

    public static final Codec<PathData> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.optionalFieldOf("status", NONE).forGetter(d -> d.status == PREVIEW ? NONE : d.status),
        Entry.CODEC.listOf().optionalFieldOf("journal", List.of()).forGetter(d -> d.journal),
        ItemStack.CODEC.listOf().optionalFieldOf("paid", List.of()).forGetter(d -> d.paid),
        Codec.LONG.listOf().optionalFieldOf("columns", List.of()).forGetter(d -> d.columns),
        Codec.LONG.listOf().optionalFieldOf("torches", List.of()).forGetter(d -> d.torchPos),
        Codec.LONG.optionalFieldOf("built_at", 0L).forGetter(d -> d.builtAt),
        Codec.INT.optionalFieldOf("role", DOOR).forGetter(d -> d.role),
        BlockPos.CODEC.optionalFieldOf("door").forGetter(d -> Optional.ofNullable(d.door)),
        BlockPos.CODEC.listOf().optionalFieldOf("waypoints", List.of()).forGetter(d -> d.waypoints)).apply(i, PathData::of));

    private static PathData of(int status, List<Entry> journal, List<ItemStack> paid, List<Long> columns, List<Long> torches, long builtAt, int role, Optional<BlockPos> door,
                               List<BlockPos> waypoints) {
        PathData d = new PathData(() -> {});
        d.status = status;
        d.journal.addAll(journal);
        d.paid.addAll(paid);
        d.columns.addAll(columns);
        d.torchPos.addAll(torches);
        d.builtAt = builtAt;
        d.role = role;
        d.door = door.orElse(null);
        d.waypoints.addAll(waypoints);
        return d;
    }

    public int status = NONE;
    public final List<Entry> journal = new ArrayList<>();
    public final List<ItemStack> paid = new ArrayList<>();
    /** Road columns this path marked (packed x/z), unmarked again when the path is taken up. */
    public final List<Long> columns = new ArrayList<>();
    /** Wall torches the tunnel got and lanterns on bridge posts (BlockPos longs): only those still standing are paid back (old builds). */
    public final List<Long> torchPos = new ArrayList<>();
    /** Game time the path was built (0 = not built, or built before 0.1.20: never made permanent by itself). */
    public long builtAt;
    /** A stake is the door's (the first one, which builds) or a waypoint placed to steer the route (then `door` is its path's start, and the start lists its waypoints in route order). */
    public int role = DOOR;
    public BlockPos door;
    public final List<BlockPos> waypoints = new ArrayList<>();
    /** The surveyed path while it is only a preview; not saved (a reload means a fresh survey). */
    public transient HousePaths.Survey survey;
    /** Where a path from a door or fence gate starts (the cell outside it); null for a stake, which starts where it stands. */
    public transient BlockPos start;
    private transient Runnable dirty;

    public PathData(Runnable dirty) {
        this.dirty = dirty;
    }

    void dirty(Runnable dirty) {
        this.dirty = dirty;
    }

    public void setChanged() {
        dirty.run();
    }

    /** Forgets everything that lets the path be undone (it is permanent now). */
    void forget() {
        journal.clear();
        paid.clear();
        columns.clear();
        torchPos.clear();
        builtAt = 0;
        status = NONE;
        setChanged();
    }
}
