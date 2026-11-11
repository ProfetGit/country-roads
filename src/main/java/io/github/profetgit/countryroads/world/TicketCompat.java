package io.github.profetgit.countryroads.world;

import io.github.profetgit.countryroads.CountryRoads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Chunk tickets: the survey ticket (a registered TicketType on 26.x, a created one on 1.21.1) and the keep-alive ticket. */
public final class TicketCompat {
    //? if >=26.1 {
    private static net.minecraft.server.level.TicketType survey;
    //?} else {
    /*private static net.minecraft.server.level.TicketType<ChunkPos> survey;
    *///?}

    private TicketCompat() {}

    /** Keeps fetched chunks loaded while a survey runs; not saved with the world, expires if a survey forgets it. */
    public static void register(CountryRoads.Registrar r) {
        //? if >=26.1 {
        survey = r.add(net.minecraft.core.registries.Registries.TICKET_TYPE, net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.TICKET_TYPE,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(CountryRoads.ID, "survey")),
            new net.minecraft.server.level.TicketType(400L, net.minecraft.server.level.TicketType.FLAG_LOADING));
        //?} else {
        /*survey = net.minecraft.server.level.TicketType.create(CountryRoads.ID + ":survey", java.util.Comparator.comparingLong(ChunkPos::toLong), 400);
        *///?}
    }

    public static void addSurvey(ServerLevel level, ChunkPos pos) {
        //? if >=26.1 {
        level.getChunkSource().addTicketWithRadius(survey, pos, 0);
        //?} else {
        /*level.getChunkSource().addRegionTicket(survey, pos, 0, pos);
        *///?}
    }

    public static void removeSurvey(ServerLevel level, ChunkPos pos) {
        //? if >=26.1 {
        level.getChunkSource().removeTicketWithRadius(survey, pos, 0);
        //?} else {
        /*level.getChunkSource().removeRegionTicket(survey, pos, 0, pos);
        *///?}
    }

    /** Refreshes the short-lived portal ticket on a chunk a rollout is laying into. */
    public static void keepLoaded(ServerLevel level, ChunkPos pos) {
        //? if >=26.1 {
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, pos, 0);
        //?} else {
        /*level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.PORTAL, pos, 0, pos.getWorldPosition());
        *///?}
    }
}
