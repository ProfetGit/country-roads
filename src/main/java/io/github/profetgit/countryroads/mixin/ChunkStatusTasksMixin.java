package io.github.profetgit.countryroads.mixin;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.gen.GenContext;
import io.github.profetgit.countryroads.paint.RoadPainter;
import io.github.profetgit.countryroads.plan.ChunkMask;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.StaticCache2D;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {
    @Inject(method = "generateFeatures", at = @At("HEAD"))
    private static void countryroads$begin(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            GenContext.begin(context.level(), chunk.getPos());
        } catch (Throwable t) {
            GenContext.end();
            CountryRoads.LOG.error("Road planning failed for chunk {}", chunk.getPos(), t);
        }
    }

    @Inject(method = "generateFeatures", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/blending/Blender;generateBorderTicks(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"))
    private static void countryroads$paint(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            ChunkMask m = GenContext.mask(chunk.getPos());
            var network = io.github.profetgit.countryroads.plan.RoadNetwork.get(context.level());
            if (m != null) RoadPainter.paint(chunk, m, network == null ? java.util.List.of() : network.streets(chunk.getPos().x(), chunk.getPos().z()),
                (x, z) -> countryroads$neighbourTop(chunks, x, z));
            else if (network != null)
                io.github.profetgit.countryroads.world.RoadColumns.store(chunk, io.github.profetgit.countryroads.world.RoadColumns.empty());
        } catch (Throwable t) {
            CountryRoads.LOG.error("Road painting failed for chunk {}", chunk.getPos(), t);
        } finally {
            GenContext.end();
        }
    }

    /** Surface of an already painted connector column in a neighbouring chunk, or RoadPainter.NO_TOP. */
    private static int countryroads$neighbourTop(StaticCache2D<GenerationChunkHolder> chunks, int x, int z) {
        try {
            int cx = x >> 4, cz = z >> 4;
            if (!chunks.contains(cx, cz)) return RoadPainter.NO_TOP;
            ChunkAccess nb = chunks.get(cx, cz).getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
            if (nb == null || !io.github.profetgit.countryroads.world.RoadColumns.handled(nb)) return RoadPainter.NO_TOP;
            ChunkMask nm = GenContext.mask(nb.getPos());
            if (nm == null) return RoadPainter.NO_TOP;
            int i = ChunkMask.idx(x & 15, z & 15);
            if (!nm.conn[i] || nm.in[i] < 0 || nm.connD[i] >= RoadPainter.CONN_EASE || nm.kind[i] != io.github.profetgit.countryroads.plan.EdgePlan.GROUND) return RoadPainter.NO_TOP;
            net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            int from = Math.round(nm.y[i]) + 10, to = Math.max(nb.getMinY(), from - 40);
            for (int y = from; y >= to; y--)
                if (io.github.profetgit.countryroads.paint.PaintRules.terrain(nb.getBlockState(pos.set(x, y, z)))) return y;
            return RoadPainter.NO_TOP;
        } catch (RuntimeException e) {
            return RoadPainter.NO_TOP;
        }
    }
}
