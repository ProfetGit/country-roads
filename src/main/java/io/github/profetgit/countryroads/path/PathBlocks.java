package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.profetgit.countryroads.CountryRoads;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Where a house path put a block that has item value (steps, bridges, lanterns, stone): the path is free, so these
 * drop nothing when a player breaks them (no farming a free bridge). Dirt-type blocks and plants drop as usual. An
 * entry is only honoured while the same block still stands there. Server thread only.
 */
public final class PathBlocks extends SavedData {
    private record Entry(long pos, Identifier block) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
            Identifier.CODEC.fieldOf("block").forGetter(Entry::block)).apply(i, Entry::new));
    }

    public static final Codec<PathBlocks> CODEC = Entry.CODEC.listOf().xmap(PathBlocks::new, p -> {
        List<Entry> out = new java.util.ArrayList<>(p.blocks.size());
        p.blocks.long2ObjectEntrySet().forEach(e -> out.add(new Entry(e.getLongKey(), BuiltInRegistries.BLOCK.getKey(e.getValue()))));
        return out;
    });
    private static final Map<String, SavedDataType<PathBlocks>> TYPES = new ConcurrentHashMap<>();
    /** Show the explanation again after this many ticks without a blocked drop (10 minutes). */
    static final long NOTICE_GAP = 12000;
    private static final Map<UUID, Long> NOTICED = new ConcurrentHashMap<>();

    private final Long2ObjectOpenHashMap<Block> blocks = new Long2ObjectOpenHashMap<>();

    public PathBlocks() {}

    private PathBlocks(List<Entry> list) {
        for (Entry e : list) BuiltInRegistries.BLOCK.getOptional(e.block).ifPresent(b -> blocks.put(e.pos, b));
    }

    private static SavedDataType<PathBlocks> type(ServerLevel level) {
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return TYPES.computeIfAbsent(dim, d -> new SavedDataType<>(Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_blocks_" + d), PathBlocks::new, CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE));
    }

    public static PathBlocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(type(level));
    }

    /** Blocks that drop as usual: free in survival anyway. */
    static boolean droppable(BlockState s) {
        return s.isAir() || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.GRASS_BLOCK)
            || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM);
    }

    /** The path now has `to` at pos (journaled change): remember it when it must not drop. */
    void put(BlockPos pos, BlockState to, boolean free) {
        if (free || droppable(to)) {
            if (blocks.remove(pos.asLong()) != null) setDirty();
        } else if (blocks.put(pos.asLong(), to.getBlock()) != to.getBlock()) setDirty();
    }

    void forget(BlockPos pos) {
        if (blocks.remove(pos.asLong()) != null) setDirty();
    }

    /** A tracked block still stands at pos: whatever it drops (it popped off its support, an explosion) is thrown away. */
    private static boolean dropless(ServerLevel level, BlockPos pos) {
        Block mine = of(level).blocks.get(pos.asLong());
        return mine != null && level.getBlockState(pos).getBlock() == mine;
    }

    /** An item entity entering the world that a tracked block dropped: the loader discards (or refuses) it. */
    public static boolean droppedItem(net.minecraft.world.entity.Entity entity, ServerLevel world) {
        return entity instanceof net.minecraft.world.entity.item.ItemEntity && dropless(world, entity.blockPosition());
    }

    /** A player is about to break a block: false cancels the break (a tracked block is removed here, without drops). */
    public static boolean beforeBreak(net.minecraft.world.level.Level world, net.minecraft.world.entity.player.Player player, BlockPos pos, BlockState state) {
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) return true;
        PathBlocks pb = of(level);
        Block mine = pb.blocks.get(pos.asLong());
        if (mine == null) return true;
        pb.forget(pos);
        if (mine != state.getBlock() || sp.getAbilities().instabuild) return true;
        level.destroyBlock(pos, false, player);
        long now = level.getGameTime();
        Long last = NOTICED.put(sp.getUUID(), now);
        if (last == null || now - last > NOTICE_GAP) PathNotice.send(sp, PathNotice.NODROP, 0, 0, 0, false);
        return false;
    }
}
