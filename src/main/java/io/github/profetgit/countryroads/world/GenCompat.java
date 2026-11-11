package io.github.profetgit.countryroads.world;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** The worldgen calls whose shape differs between Minecraft versions. */
public final class GenCompat {
    private GenCompat() {}

    public interface Biomes {
        Holder<Biome> at(int quartX, int quartY, int quartZ);
    }

    public static StructureTemplateManager templates(ServerLevel level) {
        //? if >=26.3 {
        return level.getStructureTemplateManager();
        //?} else {
        /*return level.getServer().getStructureManager();
        *///?}
    }

    public static Climate.Sampler sampler(RandomState rs) {
        //? if >=26.3 {
        return rs.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build());
        //?} else {
        /*return rs.sampler();
        *///?}
    }

    public static Biomes biomes(ChunkGenerator generator, RandomState rs) {
        //? if >=26.3 {
        var resolver = generator.getBiomeSource().createCachingResolver(rs);
        return resolver::getNoiseBiome;
        //?} else {
        /*var sampler = rs.sampler();
        var source = generator.getBiomeSource();
        return (x, y, z) -> source.getNoiseBiome(x, y, z, sampler);
        *///?}
    }

    public static Structure.GenerationContext context(ServerLevel level, ChunkGenerator generator, Climate.Sampler sampler, RandomState rs,
                                                      long seed, ChunkPos chunk, Predicate<Holder<Biome>> valid) {
        //? if >=26.3 {
        return new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), sampler, rs,
            templates(level), seed, chunk, level, valid);
        //?} else {
        /*return new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), rs,
            templates(level), seed, chunk, level, valid);
        *///?}
    }

    public static StructureStart generate(ServerLevel level, Holder<Structure> holder, ChunkGenerator generator, Climate.Sampler sampler,
                                          RandomState rs, long seed, ChunkAccess chunk, Predicate<Holder<Biome>> valid) {
        //? if >=26.3 {
        return holder.value().generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            sampler, rs, templates(level), seed, chunk.getPos(), 0, chunk, valid);
        //?} else if >=26.1 {
        /*return holder.value().generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            rs, templates(level), seed, chunk.getPos(), 0, chunk, valid);
        *///?} else {
        /*return holder.value().generate(level.registryAccess(), generator, generator.getBiomeSource(),
            rs, templates(level), seed, chunk.getPos(), 0, chunk, valid);
        *///?}
    }

    /** The structure template a single pool element places, or null. */
    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template(StructureTemplateManager templates,
                                                                                                        net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single) {
        //? if >=26.1 {
        return templates.get(single.getTemplateLocation()).orElse(null);
        //?} else {
        /*return ((io.github.profetgit.countryroads.mixin.SinglePoolElementAccessor) single).countryroads$template()
            .map(loc -> templates.get(loc).orElse(null), t -> t);
        *///?}
    }
}
