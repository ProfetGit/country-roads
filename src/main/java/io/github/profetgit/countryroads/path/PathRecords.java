package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.profetgit.countryroads.CountryRoads;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per dimension: the paths started from a door or fence gate (their PathData, keyed by the door's position) and the
 * time every built path (door or stake) was built, so it can be made permanent after a day. Server thread only.
 */
public final class PathRecords extends SavedData {
    private record Door(long pos, PathData data) {
        static final Codec<Door> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("pos").forGetter(Door::pos), PathData.CODEC.fieldOf("data").forGetter(Door::data)).apply(i, Door::new));
    }

    private record Built(long pos, long at) {
        static final Codec<Built> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("pos").forGetter(Built::pos), Codec.LONG.fieldOf("at").forGetter(Built::at)).apply(i, Built::new));
    }

    public static final Codec<PathRecords> CODEC = RecordCodecBuilder.create(i -> i.group(
        Door.CODEC.listOf().optionalFieldOf("doors", List.of()).forGetter(r -> r.doors.entrySet().stream().map(e -> new Door(e.getKey(), e.getValue())).toList()),
        Built.CODEC.listOf().optionalFieldOf("built", List.of()).forGetter(r -> r.built.long2LongEntrySet().stream().map(e -> new Built(e.getLongKey(), e.getLongValue())).toList())
    ).apply(i, PathRecords::new));
    private static final Map<String, SavedDataType<PathRecords>> TYPES = new ConcurrentHashMap<>();

    private final Map<Long, PathData> doors = new HashMap<>();
    private final Long2LongOpenHashMap built = new Long2LongOpenHashMap();

    public PathRecords() {}

    private PathRecords(List<Door> doorList, List<Built> builtList) {
        for (Door d : doorList) {
            d.data.dirty(this::setDirty);
            doors.put(d.pos, d.data);
        }
        for (Built b : builtList) built.put(b.pos, b.at);
    }

    private static SavedDataType<PathRecords> type(ServerLevel level) {
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return TYPES.computeIfAbsent(dim, d -> new SavedDataType<>(Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_records_" + d), PathRecords::new, CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE));
    }

    public static PathRecords of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(type(level));
    }

    PathData door(BlockPos anchor) {
        return doors.get(anchor.asLong());
    }

    PathData open(BlockPos anchor) {
        return doors.computeIfAbsent(anchor.asLong(), k -> new PathData(this::setDirty));
    }

    void drop(BlockPos anchor) {
        if (doors.remove(anchor.asLong()) != null) setDirty();
        unmark(anchor);
    }

    void mark(BlockPos anchor, long time) {
        built.put(anchor.asLong(), time);
        setDirty();
    }

    void unmark(BlockPos anchor) {
        if (built.containsKey(anchor.asLong())) {
            built.remove(anchor.asLong());
            setDirty();
        }
    }

    /** Built paths older than `age` ticks. */
    long[] older(long now, long age) {
        LongArrayList out = new LongArrayList();
        for (var e : built.long2LongEntrySet()) if (now - e.getLongValue() >= age) out.add(e.getLongKey());
        return out.toLongArray();
    }

    long builtAt(BlockPos anchor) {
        return built.getOrDefault(anchor.asLong(), 0L);
    }
}
