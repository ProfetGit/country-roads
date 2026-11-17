package io.github.profetgit.countryroads.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * Predicts the other structures a road must keep out of (outposts, ruined portals, temples, igloos, huts, mansions,
 * modded surface structures, and mineshafts, whose badlands variant stands on the surface), the way VillageOracle
 * replays ChunkGenerator.createStructures for villages: same weighted pick, same findValidGenerationPoint, pieces from
 * the stub. A pure function of seed and position, so every chunk and every plan sees the same boxes.
 */
public final class StructureOracle {
    /** A predicted structure: its piece boxes, and whether the router steers around it (surface step) or only the painter keeps off it. */
    public record Site(String id, List<BoundingBox> boxes, boolean route) {}

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState state;
    private final RandomState randomState;
    private final List<Holder<StructureSet>> sets;
    private final ThreadLocal<Climate.Sampler> sampler;
    private final ConcurrentHashMap<Long, List<Site>>[] memo;

    @SuppressWarnings("unchecked")
    public StructureOracle(ServerLevel level, Set<String> villageExtras) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.state = level.getChunkSource().getGeneratorState();
        this.randomState = level.getChunkSource().randomState();
        this.sets = state.possibleStructureSets().stream()
            .filter(set -> set.value().placement() instanceof RandomSpreadStructurePlacement)
            .filter(set -> set.value().structures().stream().anyMatch(e -> wanted(e.structure(), villageExtras)))
            .toList();
        this.sampler = ThreadLocal.withInitial(() -> GenCompat.sampler(randomState));
        this.memo = new ConcurrentHashMap[sets.size()];
        for (int i = 0; i < memo.length; i++) memo[i] = new ConcurrentHashMap<>();
        this.extras = Set.copyOf(villageExtras);
    }

    private final Set<String> extras;

    static boolean surface(Holder<Structure> s) {
        return s.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    static boolean mineshaft(Holder<Structure> s) {
        return s.unwrapKey().map(k -> k.identifier().getPath().contains("mineshaft")).orElse(false);
    }

    static boolean wanted(Holder<Structure> s, Set<String> villageExtras) {
        return !VillageOracle.isVillage(s, villageExtras) && (surface(s) || mineshaft(s));
    }

    public List<String> setNames() {
        return sets.stream().map(s -> s.unwrapKey().map(k -> k.identifier().toString()).orElse("?")).toList();
    }

    /** Structures whose start chunk lies in [minCx..maxCx] x [minCz..maxCz]; routeOnly: only sets with a surface structure. */
    public List<Site> find(int minCx, int minCz, int maxCx, int maxCz, boolean routeOnly) {
        List<Site> out = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            if (routeOnly && sets.get(i).value().structures().stream().noneMatch(e -> surface(e.structure()))) continue;
            RandomSpreadStructurePlacement spread = (RandomSpreadStructurePlacement) sets.get(i).value().placement();
            int sp = spread.spacing();
            for (int gx = Math.floorDiv(minCx, sp); gx <= Math.floorDiv(maxCx, sp); gx++)
                for (int gz = Math.floorDiv(minCz, sp); gz <= Math.floorDiv(maxCz, sp); gz++) {
                    ChunkPos c = spread.getPotentialStructureChunk(state.getLevelSeed(), gx * sp, gz * sp);
                    if (c.x() < minCx || c.x() > maxCx || c.z() < minCz || c.z() > maxCz) continue;
                    int set = i;
                    out.addAll(memo[i].computeIfAbsent(c.pack(), k -> compute(sets.get(set).value(), c)));
                }
        }
        return out;
    }

    private List<Site> compute(StructureSet set, ChunkPos c) {
        if (!set.placement().isStructureChunk(state, c.x(), c.z())) return List.of();
        List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.structures());
        if (options.size() == 1) {
            List<Site> s = tryGenerate(options.getFirst(), c);
            return s == null ? List.of() : s;
        }
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(state.getLevelSeed(), c.x(), c.z());
        int total = 0;
        for (var o : options) total += o.weight();
        while (!options.isEmpty()) {
            int choice = random.nextInt(total);
            int index = 0;
            for (var o : options) {
                choice -= o.weight();
                if (choice < 0) break;
                index++;
            }
            var selected = options.get(index);
            List<Site> s = tryGenerate(selected, c);
            if (s != null) return s;
            options.remove(index);
            total -= selected.weight();
        }
        return List.of();
    }

    /** null when this option fails (try the next); empty when a structure we ignore wins the chunk. */
    private List<Site> tryGenerate(StructureSet.StructureSelectionEntry entry, ChunkPos c) {
        Structure structure = entry.structure().value();
        boolean want = wanted(entry.structure(), extras);
        List<BoundingBox> boxes = VillageOracle.jigsaw(() -> {
            var ctx = GenCompat.context(level, generator, sampler.get(), randomState, state.getLevelSeed(), c, structure.biomes()::contains);
            var stub = structure.findValidGenerationPoint(ctx);
            if (stub.isEmpty()) return null;
            if (!want) return List.<BoundingBox>of();
            List<BoundingBox> b = new ArrayList<>();
            for (StructurePiece p : stub.get().getPiecesBuilder().build().pieces()) b.add(p.getBoundingBox());
            return b;
        });
        if (boxes == null) return null;
        if (boxes.isEmpty()) return List.of();
        String id = entry.structure().unwrapKey().map(k -> k.identifier().toString()).orElse("?");
        return List.of(new Site(id, List.copyOf(boxes), surface(entry.structure())));
    }
}
