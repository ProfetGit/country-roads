package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.paint.Palette;
import io.github.profetgit.countryroads.world.TerrainSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Wayside stops: vanilla abandoned_camp structures placed beside long roads, rarely (about one per 1250 road blocks,
 * never near a natural camp). The camp is a real structure start, so terrain adaptation, loot and /locate all work.
 */
public final class Camps {
    static final int PER_BLOCKS = 1250, MIN_ROAD = 500;
    private static final Map<String, String> FALLBACK = Map.of(
        "temperate", "meadow", "taiga", "taiga", "snowy", "snowy_taiga", "savanna", "savanna", "swamp", "swamp",
        "jungle", "sparse_jungle", "badlands", "wooded_badlands", "stony", "windswept_forest");

    private final ServerLevel level;
    private final TerrainSampler terrain;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState state;
    private final Holder<StructureSet> campSet;
    private final List<Holder<Structure>> variants = new ArrayList<>();
    private final ThreadLocal<Climate.Sampler> sampler;

    public Camps(ServerLevel level, TerrainSampler terrain) {
        this.level = level;
        this.terrain = terrain;
        this.generator = level.getChunkSource().getGenerator();
        this.state = level.getChunkSource().getGeneratorState();
        Holder<StructureSet> set = null;
        for (Holder<StructureSet> s : state.possibleStructureSets())
            if (s.unwrapKey().map(k -> k.identifier().getPath().equals("abandoned_camps") || k.identifier().getPath().startsWith("abandoned_camp")).orElse(false)) set = s;
        this.campSet = set;
        if (set != null) for (var e : set.value().structures()) variants.add(e.structure());
        var rs = level.getChunkSource().randomState();
        this.sampler = ThreadLocal.withInitial(() -> rs.createClimateSampler(SamplerContext.builder().enableCaches().build()));
    }

    public boolean available() {
        return campSet != null && !variants.isEmpty();
    }

    /** Picks at most one camp for the plan: a dry, gentle stretch, 14+ blocks beside the road. */
    void choose(EdgePlan p) {
        if (!available() || p.length() < MIN_ROAD) return;
        long h = p.edge.seed() * 0x2545F4914F6CDD1DL;
        h ^= h >>> 31;
        if (Math.floorMod(h, PER_BLOCKS) >= p.length()) return;
        int n = p.length();
        int start = n / 4 + (int) Math.floorMod(h >>> 20, (long) Math.max(1, n / 2));
        for (int tries = 0; tries < 24; tries++) {
            int i = Math.floorMod(start + tries * 37, n - 80) + 40;
            if (!gentle(p, i)) continue;
            int side = ((h >>> (tries + 3)) & 1) == 0 ? 1 : -1;
            int a = i - 3, b = i + 3;
            float tx = p.x[b] - p.x[a], tz = p.z[b] - p.z[a];
            float len = (float) Math.hypot(tx, tz);
            float rx = -tz / len * side, rz = tx / len * side;
            int sx = (int) Math.floor(p.x[i] + rx * 20), sz = (int) Math.floor(p.z[i] + rz * 20);
            ChunkPos c = new ChunkPos(sx >> 4, sz >> 4);
            if (state.hasStructureChunkInRange(campSet, c.x(), c.z(), 4)) return;
            Holder<Structure> variant = variant(sx, sz);
            if (variant == null) return;
            List<BoundingBox> boxes = pieces(variant, c);
            if (boxes.isEmpty() || tooClose(p, boxes)) continue;
            p.campChunk = c.pack();
            p.camp = variant.unwrapKey().map(k -> k.identifier()).orElse(null);
            p.campBoxes = boxes;
            return;
        }
    }

    private static boolean gentle(EdgePlan p, int i) {
        for (int k = i - 20; k <= i + 20; k++) if (p.kind[k] != EdgePlan.GROUND) return false;
        return Math.abs(p.y[i + 20] - p.y[i - 20]) < 5;
    }

    private static boolean tooClose(EdgePlan p, List<BoundingBox> boxes) {
        for (BoundingBox b : boxes)
            for (int i = 0; i < p.length(); i += 2)
                if (p.x[i] > b.minX() - 9 && p.x[i] < b.maxX() + 10 && p.z[i] > b.minZ() - 9 && p.z[i] < b.maxZ() + 10) return true;
        return false;
    }

    private Holder<Structure> variant(int x, int z) {
        TerrainSampler.Sample s = terrain.sample(x, z);
        if (s.wet()) return null;
        Holder<Biome> biome = terrain.biome(x, s.ground(), z);
        for (Holder<Structure> v : variants) if (v.value().biomes().contains(biome)) return v;
        String want = FALLBACK.get(Palette.surface(biome).name());
        if (want == null) return null;
        for (Holder<Structure> v : variants)
            if (v.unwrapKey().map(k -> k.identifier().getPath().endsWith("_" + want)).orElse(false)) return v;
        return null;
    }

    private List<BoundingBox> pieces(Holder<Structure> variant, ChunkPos c) {
        var ctx = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), sampler.get(),
            level.getChunkSource().randomState(), level.getStructureTemplateManager(), state.getLevelSeed(), c, level, b -> true);
        List<BoundingBox> out = new ArrayList<>();
        var built = io.github.profetgit.countryroads.world.VillageOracle.jigsaw(
            () -> variant.value().findValidGenerationPoint(ctx).map(stub -> stub.getPiecesBuilder().build().pieces()).orElse(List.of()));
        for (StructurePiece piece : built) out.add(piece.getBoundingBox());
        return out;
    }

    /** At STRUCTURE_STARTS: adds the camp start when this chunk is a chosen camp chunk. */
    public void addStart(ChunkAccess chunk, EdgePlan p) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Identifier id = p.camp;
        var holder = id == null ? null : registry.get(id).orElse(null);
        if (holder == null) return;
        StructureStart existing = chunk.getStartForStructure(holder.value());
        if (existing != null && existing.isValid()) return;
        StructureStart start = holder.value().generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            sampler.get(), level.getChunkSource().randomState(), level.getStructureTemplateManager(), state.getLevelSeed(), chunk.getPos(), 0, chunk, b -> true);
        if (start.isValid()) chunk.setStartForStructure(holder.value(), start);
    }
}
