package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.CountryRoads;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Server to client: what the player's stake path is doing, as a code and up to three numbers. The client turns it into
 * a short card beside the crosshair (client/PathHud); no sentence ever travels or goes to the chat.
 */
public record PathNotice(int code, int a, int b, int c, boolean flag) implements CustomPacketPayload {
    public static final Type<PathNotice> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CountryRoads.ID, "path_notice"));

    /** Where a path stands (kept until it is built, taken up or surveyed away). */
    public static final int SURVEYING = 0, PREVIEW = 1, BUILT = 2, BUILDING = 3, CLEAR = 4;
    /** One-off news: a = stake number / length / distance. */
    public static final int WAYPOINT = 5, END_ON = 6, END_OFF = 7, NEEDS_TWO = 8, UNDONE = 9, COMMITTED = 10, NODROP = 11, START_FAR = 12;
    /** Why there is no path. */
    public static final int DOOR_BOXED = 13, BOXED_IN = 14, NO_ROUTE = 15, NO_BRIDGE = 16, NO_ROAD = 17, NO_WAYPOINT = 18, ON_ROAD = 19, DISABLED = 20;

    public static final StreamCodec<RegistryFriendlyByteBuf, PathNotice> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeByte(p.code);
            buf.writeVarInt(p.a);
            buf.writeVarInt(p.b);
            buf.writeVarInt(p.c);
            buf.writeBoolean(p.flag);
        },
        buf -> new PathNotice(buf.readUnsignedByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<PathNotice> type() {
        return TYPE;
    }

    /** Set by the loader entry: whether the player's client takes the notice, and sending it. */
    public static Predicate<ServerPlayer> CAN_SEND = p -> false;
    public static BiConsumer<ServerPlayer, PathNotice> SEND = (p, n) -> {};
    /** Set by the client entry on loaders whose payload handler is registered in common code. */
    public static Consumer<PathNotice> RECEIVE = n -> {};

    /** To the player (a test without one only logs it). Clients without the mod's HUD get nothing. */
    static void send(ServerPlayer player, int code, int a, int b, int c, boolean flag) {
        if (player == null) {
            CountryRoads.LOG.info("CR-STAKE-MSG code=" + code + " a=" + a + " b=" + b + " c=" + c + " flag=" + flag);
            return;
        }
        PathNotice n = new PathNotice(code, a, b, c, flag);
        if (CAN_SEND.test(player)) SEND.accept(player, n);
    }

    /** The code of a failed survey (PathSearch's translation-key-style reason), or -1. */
    static int failure(String reason) {
        if (reason == null) return -1;
        String r = reason.substring(reason.lastIndexOf('.') + 1);
        if (r.endsWith("_free")) r = r.substring(0, r.length() - 5);
        return switch (r) {
            case "boxed_in" -> BOXED_IN;
            case "no_route" -> NO_ROUTE;
            case "no_bridge" -> NO_BRIDGE;
            case "no_road" -> NO_ROAD;
            case "no_waypoint" -> NO_WAYPOINT;
            case "on_road" -> ON_ROAD;
            default -> NO_ROUTE;
        };
    }
}
