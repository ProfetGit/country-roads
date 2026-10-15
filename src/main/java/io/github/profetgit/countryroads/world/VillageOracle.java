package io.github.profetgit.countryroads.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Predicts where villages generate, without generating chunks, by replaying ChunkGenerator.createStructures for the
 * structure sets that contain a village. Thread-safe; results are memoised per candidate chunk.
 */
public final class VillageOracle {
    public record Village(Identifier structure, ChunkPos chunk, BlockPos pos) {
        public long key() {
            return chunk.pack();
        }
    }

    /** Piece boxes of a generated village; streets are the jigsaw pieces whose template path has "/streets/". */
    public record Footprint(List<BoundingBox> pieces, List<BoundingBox> streets, StreetBands bands, List<BoundingBox> others,
                            List<List<int[]>> doorWalks, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap doorCols) {
        /** A column straight out in front of a door of a village without streets (where its road docks). */
        public boolean onDoorWalk(int x, int z) {
            return doorCols.containsKey(StreetBands.pack(x, z));
        }

        /** The floor (block under the door) of the door walk through this column; Integer.MIN_VALUE off the walks. */
        public int doorFloor(int x, int z) {
            return doorCols.getOrDefault(StreetBands.pack(x, z), Integer.MIN_VALUE);
        }

        public boolean contains(int x, int z, int inflate) {
            for (BoundingBox b : pieces)
                if (x >= b.minX() - inflate && x <= b.maxX() + inflate && z >= b.minZ() - inflate && z <= b.maxZ() + inflate) return true;
            return false;
        }

        /** Inside a house, farm, well or other non-street piece box (street and terminator boxes do not count). */
        public boolean inOther(int x, int z, int inflate) {
            for (BoundingBox b : others)
                if (x >= b.minX() - inflate && x <= b.maxX() + inflate && z >= b.minZ() - inflate && z <= b.maxZ() + inflate) return true;
            return false;
        }
    }

    /** Modded villages not in #minecraft:village; resolved only when present. */
    public static final Set<String> KNOWN_MODDED = Set.of(
        "ctov:village_plains", "ctov:village_desert", "ctov:village_savanna", "ctov:village_snowy", "ctov:village_taiga",
        "towns_and_towers:village_badlands", "towns_and_towers:village_jungle", "towns_and_towers:village_swamp");

    private final ServerLevel level;
    private final Set<String> extraIds;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState state;
    private final RandomState randomState;
    private final List<Holder<StructureSet>> sets;
    private final ThreadLocal<Climate.Sampler> sampler;
    private final ConcurrentHashMap<Long, Optional<Village>>[] memo;

    @SuppressWarnings("unchecked")
    public VillageOracle(ServerLevel level, Set<String> extraIds) {
        this.level = level;
        this.extraIds = Set.copyOf(extraIds);
        this.generator = level.getChunkSource().getGenerator();
        this.state = level.getChunkSource().getGeneratorState();
        this.randomState = level.getChunkSource().randomState();
        this.sets = state.possibleStructureSets().stream()
            .filter(set -> set.value().structures().stream().anyMatch(e -> isVillage(e.structure(), extraIds)))
            .toList();
        this.sampler = ThreadLocal.withInitial(() -> randomState.createClimateSampler(SamplerContext.builder().enableCaches().build()));
        this.memo = new ConcurrentHashMap[sets.size()];
        for (int i = 0; i < memo.length; i++) memo[i] = new ConcurrentHashMap<>();
    }

    static boolean isVillage(Holder<Structure> s, Set<String> extraIds) {
        if (s.is(StructureTags.VILLAGE)) return true;
        return s.unwrapKey().map(k -> {
            String id = k.identifier().toString();
            return KNOWN_MODDED.contains(id) || extraIds.contains(id);
        }).orElse(false);
    }

    public int setCount() {
        return sets.size();
    }

    public List<String> setNames() {
        return sets.stream().map(s -> s.unwrapKey().map(k -> k.identifier().toString()).orElse("?")).toList();
    }

    /** Villages whose start chunk lies in [minCx..maxCx] x [minCz..maxCz], sorted by chunk. */
    public List<Village> find(int minCx, int minCz, int maxCx, int maxCz) {
        List<Village> out = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            StructurePlacement placement = sets.get(i).value().placement();
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int sp = spread.spacing();
                for (int gx = Math.floorDiv(minCx, sp); gx <= Math.floorDiv(maxCx, sp); gx++) {
                    for (int gz = Math.floorDiv(minCz, sp); gz <= Math.floorDiv(maxCz, sp); gz++) {
                        ChunkPos c = spread.getPotentialStructureChunk(state.getLevelSeed(), gx * sp, gz * sp);
                        if (c.x() < minCx || c.x() > maxCx || c.z() < minCz || c.z() > maxCz) continue;
                        at(i, c).ifPresent(out::add);
                    }
                }
            } else {
                for (int cx = minCx; cx <= maxCx; cx++)
                    for (int cz = minCz; cz <= maxCz; cz++)
                        at(i, new ChunkPos(cx, cz)).ifPresent(out::add);
            }
        }
        out.sort(Comparator.comparingInt((Village v) -> v.chunk().x()).thenComparingInt(v -> v.chunk().z()));
        return out;
    }

    private final ConcurrentHashMap<Long, Footprint> footprints = new ConcurrentHashMap<>();

    /**
     * Jigsaw piece generation reads StructureTemplate palettes whose block cache is a plain HashMap. Our planner
     * threads take turns, and a ConcurrentModificationException from a vanilla worldgen thread racing us is retried.
     */
    public static final Object JIGSAW = new Object();

    public static <T> T jigsaw(java.util.function.Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                synchronized (JIGSAW) {
                    return work.get();
                }
            } catch (java.util.ConcurrentModificationException e) {
                last = e;
                Thread.onSpinWait();
            }
        }
        throw last;
    }

    /** The village's pieces, generated the way Structure.generate does (deterministic, no chunk access). */
    public Footprint footprint(Village v) {
        return footprints.computeIfAbsent(v.key(), k -> {
            List<BoundingBox> pieces = new ArrayList<>();
            List<BoundingBox> streets = new ArrayList<>();
            List<PoolElementStructurePiece> bandPieces = new ArrayList<>();
            List<BoundingBox> others = new ArrayList<>();
            List<PoolElementStructurePiece> allPieces = new ArrayList<>();
            var holder = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(v.structure());
            if (holder.isPresent()) {
                Structure structure = holder.get().value();
                var ctx = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), sampler.get(),
                    randomState, level.getStructureTemplateManager(), state.getLevelSeed(), v.chunk(), level, structure.biomes()::contains);
                var built = jigsaw(() -> structure.findValidGenerationPoint(ctx).map(stub -> stub.getPiecesBuilder().build().pieces()).orElse(List.of()));
                for (StructurePiece p : built) {
                    pieces.add(p.getBoundingBox());
                    if (p instanceof PoolElementStructurePiece pe) {
                        allPieces.add(pe);
                        String el = pe.getElement().toString();
                        boolean street = isStreet(pe, el);
                        if (street) streets.add(p.getBoundingBox());
                        if (street || el.contains("/terminators/")) bandPieces.add(pe);
                        else others.add(p.getBoundingBox());
                    } else others.add(p.getBoundingBox());
                }
            }
            if (pieces.isEmpty()) pieces.add(new BoundingBox(v.pos().getX() - 8, 0, v.pos().getZ() - 8, v.pos().getX() + 8, 0, v.pos().getZ() + 8));
            StreetBands bands = jigsaw(() -> StreetBands.of(level.getStructureTemplateManager(), bandPieces));
            List<List<int[]>> walks = bands.isEmpty() ? jigsaw(() -> DoorWalks.of(level.getStructureTemplateManager(), allPieces, pieces)) : List.of();
            var doorCols = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            for (List<int[]> w : walks) for (int[] c : w) doorCols.put(StreetBands.pack(c[0], c[1]), c[2]);
            return new Footprint(List.copyOf(pieces), List.copyOf(streets), bands, List.copyOf(others), walks, doorCols);
        });
    }

    /**
     * Vanilla, Towns and Towers and Repurposed Structures keep street templates under "/streets/"; Dungeons and Taverns
     * names them "birch_curve", "dappled_village_path3". All of them run streets through a processor list named for
     * streets (minecraft:street_plains, kaisyn:village/street_romanian, nova_structures:street_birch).
     */
    static boolean isStreet(PoolElementStructurePiece pe, String element) {
        if (element.contains("/streets/")) return true;
        if (element.contains("/terminators/")) return false;
        if (!(pe.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single)) return false;
        return ((io.github.profetgit.countryroads.mixin.SinglePoolElementAccessor) single).countryroads$processors().unwrapKey()
            .map(k -> k.identifier().getPath().contains("street")).orElse(false);
    }

    private Optional<Village> at(int setIndex, ChunkPos c) {
        return memo[setIndex].computeIfAbsent(c.pack(), k -> compute(sets.get(setIndex).value(), c));
    }

    private Optional<Village> compute(StructureSet set, ChunkPos c) {
        if (!set.placement().isStructureChunk(state, c.x(), c.z())) return Optional.empty();
        List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.structures());
        if (options.size() == 1) {
            Optional<Village> v = tryGenerate(options.getFirst(), c);
            return v == null ? Optional.empty() : v;
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
            Optional<Village> v = tryGenerate(selected, c);
            if (v != null) return v;
            options.remove(index);
            total -= selected.weight();
        }
        return Optional.empty();
    }

    /** null when this option fails (try the next); empty when a non-village structure wins the chunk. */
    private Optional<Village> tryGenerate(StructureSet.StructureSelectionEntry entry, ChunkPos c) {
        Structure structure = entry.structure().value();
        var ctx = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), sampler.get(),
            randomState, level.getStructureTemplateManager(), state.getLevelSeed(), c, level, structure.biomes()::contains);
        var stub = structure.findValidGenerationPoint(ctx);
        if (stub.isEmpty()) return null;
        if (!isVillage(entry.structure(), extraIds)) return Optional.empty();
        Identifier id = entry.structure().unwrapKey().map(k -> k.identifier()).orElse(Identifier.withDefaultNamespace("unknown"));
        return Optional.of(new Village(id, c, stub.get().position()));
    }
}
