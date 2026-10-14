package io.github.profetgit.countryroads.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** What the stake built (see PathData). */
public final class StakeBlockEntity extends BlockEntity {
    public final PathData data = new PathData(this::setChanged);

    public StakeBlockEntity(BlockPos pos, BlockState state) {
        super(Stake.ENTITY, pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.store("path", PathData.CODEC, data);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        data.journal.clear();
        data.paid.clear();
        data.columns.clear();
        data.torchPos.clear();
        PathData read = in.read("path", PathData.CODEC).orElse(null);
        if (read == null) {
            // saved before 0.1.20: the same fields, loose
            data.status = in.getIntOr("status", PathData.NONE);
            data.journal.addAll(in.read("journal", com.mojang.serialization.Codec.list(PathData.Entry.CODEC)).orElse(java.util.List.of()));
            data.paid.addAll(in.read("paid", net.minecraft.world.item.ItemStack.CODEC.listOf()).orElse(java.util.List.of()));
            data.columns.addAll(in.read("columns", com.mojang.serialization.Codec.LONG.listOf()).orElse(java.util.List.of()));
            data.torchPos.addAll(in.read("torches", com.mojang.serialization.Codec.LONG.listOf()).orElse(java.util.List.of()));
            data.builtAt = 0;
            return;
        }
        data.status = read.status;
        data.journal.addAll(read.journal);
        data.paid.addAll(read.paid);
        data.columns.addAll(read.columns);
        data.torchPos.addAll(read.torchPos);
        data.builtAt = read.builtAt;
    }
}
