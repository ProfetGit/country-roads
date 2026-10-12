package io.github.profetgit.countryroads.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** What the stake built: the original blocks (to put back) and the materials paid (to refund). */
public final class StakeBlockEntity extends BlockEntity {
    public record Entry(BlockPos pos, BlockState state) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
            BlockState.CODEC.fieldOf("state").forGetter(Entry::state)).apply(i, Entry::new));
    }

    public static final int NONE = 0, PREVIEW = 1, BUILT = 2;

    public int status = NONE;
    public final List<Entry> journal = new ArrayList<>();
    public final List<ItemStack> paid = new ArrayList<>();
    /** Road columns this path marked (packed x/z), unmarked again when the path is taken up. */
    public final List<Long> columns = new ArrayList<>();
    /** The surveyed path while it is only a preview; not saved (a reload means a fresh survey). */
    public transient HousePaths.Survey survey;

    public StakeBlockEntity(BlockPos pos, BlockState state) {
        super(Stake.ENTITY, pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("status", status == PREVIEW ? NONE : status);
        out.store("journal", Entry.CODEC.listOf(), journal);
        out.store("paid", ItemStack.CODEC.listOf(), paid);
        out.store("columns", Codec.LONG.listOf(), columns);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        status = in.getIntOr("status", NONE);
        journal.clear();
        journal.addAll(in.read("journal", Entry.CODEC.listOf()).orElse(List.of()));
        paid.clear();
        paid.addAll(in.read("paid", ItemStack.CODEC.listOf()).orElse(List.of()));
        columns.clear();
        columns.addAll(in.read("columns", Codec.LONG.listOf()).orElse(List.of()));
    }
}
