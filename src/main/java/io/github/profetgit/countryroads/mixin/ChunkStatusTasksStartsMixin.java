package io.github.profetgit.countryroads.mixin;

import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksStartsMixin {
    /** Wayside camps become real structure starts, before the level announces this chunk's starts. */
    @Inject(method = "generateStructureStarts", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;onStructureStartsAvailable(Lnet/minecraft/world/level/chunk/ChunkAccess;)V"))
    private static void countryroads$camp(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ServerLevel level = context.level();
        if (!level.getServer().getWorldGenSettings().options().generateStructures()) return;
        try {
            RoadNetwork n = RoadNetwork.get(level);
            if (n == null) return;
            EdgePlan p = n.campAt(chunk.getPos().x(), chunk.getPos().z());
            if (p != null) n.camps.addStart(chunk, p);
        } catch (Throwable t) {
            io.github.profetgit.countryroads.CountryRoads.LOG.error("Wayside camp failed for chunk {}", chunk.getPos(), t);
        }
    }
}
