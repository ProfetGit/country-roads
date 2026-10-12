package io.github.profetgit.countryroads.paint;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Old worlds: a tree standing in the road is removed whole (connected logs, then the natural leaves around them),
 * never leaving a floating canopy. Player-placed leaves (persistent) are left alone.
 */
public final class TreeClear {
    static final int MAX_LOGS = 160;

    private TreeClear() {}

    public static void fell(ServerLevel level, BlockPos start) {
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos p = todo.poll();
            if (!logs.add(p)) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = p.offset(dx, dy, dz);
                        if (!logs.contains(q) && level.getBlockState(q).is(BlockTags.LOGS)) todo.add(q);
                    }
        }
        if (logs.size() >= MAX_LOGS) return;
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs)
            for (BlockPos q : BlockPos.betweenClosed(log.offset(-4, -2, -4), log.offset(4, 4, 4))) {
                BlockState s = level.getBlockState(q);
                if (s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)
                    && s.getValue(LeavesBlock.DISTANCE) <= 6) leaves.add(q.immutable());
            }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos q : leaves) level.setBlock(q, Blocks.AIR.defaultBlockState(), flags);
        for (BlockPos q : logs) level.setBlock(q, Blocks.AIR.defaultBlockState(), flags);
    }
}
