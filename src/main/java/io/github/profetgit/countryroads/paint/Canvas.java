package io.github.profetgit.countryroads.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;

/** Where the painter writes: a chunk still generating (no updates at all), or a live chunk in a running level. */
public interface Canvas {
    BlockState get(BlockPos pos);

    void set(BlockPos pos, BlockState state);

    /** Exactly this state: no connection updates for it or its neighbours (posts that must not latch onto terrain). */
    void setExact(BlockPos pos, BlockState state);

    /** A block entity for the block just set at its position (signs). */
    void blockEntity(BlockEntity be);

    Holder<Biome> biome(int x, int y, int z);

    int minY();

    /** First free y of the world surface heightmap at this column (the top solid block is one below). */
    int surface(int x, int z);

    int maxY();

    /** Called before a log in the road's way is removed: a live canvas fells the whole tree. */
    default void beforeRemovingLog(BlockPos pos) {
    }

    static Canvas proto(ChunkAccess chunk) {
        return new Canvas() {
            public BlockState get(BlockPos pos) {
                return chunk.getBlockState(pos);
            }

            public void set(BlockPos pos, BlockState state) {
                chunk.setBlockState(pos, state, 0);
                // fences, walls, stairs and the like get their connections when the chunk is finished
                if (PaintRules.connects(state)) chunk.markPosForPostProcessing(pos);
            }

            public void setExact(BlockPos pos, BlockState state) {
                chunk.setBlockState(pos, state, 0);
            }

            public void blockEntity(BlockEntity be) {
                chunk.setBlockEntity(be);
            }

            public Holder<Biome> biome(int x, int y, int z) {
                return chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            }

            public int minY() {
                return chunk.getMinY();
            }

            public int surface(int x, int z) {
                return chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            }

            public int maxY() {
                return chunk.getMaxY();
            }
        };
    }

    static Canvas live(ServerLevel level, LevelChunk chunk) {
        return new Canvas() {
            public BlockState get(BlockPos pos) {
                return level.getBlockState(pos);
            }

            public void set(BlockPos pos, BlockState state) {
                // clients see it and connecting blocks shape themselves; no neighbour physics (no falling sand, no drops)
                level.setBlock(pos, state, Block.UPDATE_CLIENTS | (PaintRules.connects(state) ? Block.UPDATE_NEIGHBORS : Block.UPDATE_KNOWN_SHAPE));
            }

            public void setExact(BlockPos pos, BlockState state) {
                level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }

            public void blockEntity(BlockEntity be) {
                level.setBlockEntity(be);
            }

            public Holder<Biome> biome(int x, int y, int z) {
                return level.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            }

            public int minY() {
                return level.getMinY();
            }

            public int surface(int x, int z) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
            }

            public int maxY() {
                return level.getMaxY();
            }

            public void beforeRemovingLog(BlockPos pos) {
                TreeClear.fell(level, pos);
            }
        };
    }
}
