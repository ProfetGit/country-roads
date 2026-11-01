package io.github.profetgit.countryroads.client;

import io.github.profetgit.countryroads.path.PathNotice;
import io.github.profetgit.countryroads.path.Stake;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The stake path card: a headline (what the path is doing), the one or two things the player can do now as key + action
 * chips, and for a failure one line on how to fix it. It sits on the right side of the screen and shows while the player holds
 * the stake or looks at one, and for a few seconds after every change. Words come from the lang file; the server only
 * sends a code and numbers.
 */
final class PathHud {
    private static final int APRICOT = 0xF2A65A, PLUM = 0x2A1E2E, CREAM = 0xFFF1DC, DIM = 0xB9A58F, WARN = 0xE8735A, SAGE = 0x9CCB7A, SKY = 0x8FC1D9, KEY_SHADE = 0xB5733A;
    private static final long NEWS_MS = 4000, FAIL_MS = 7000, GRACE_MS = 1500, FADE_MS = 250, PREVIEW_MS = 120_000;

    /** What the path is doing (kept), as last told. */
    private static int state = -1, length, tunnel, stakes;
    private static boolean free;
    private static long stateAt;
    /** The last piece of news (shown on its own for a few seconds). */
    private static int news = -1, newsA;
    private static boolean newsFlag;
    private static long newsAt;
    private static long lookedAt;

    private PathHud() {}

    static void receive(PathNotice n) {
        long now = System.currentTimeMillis();
        switch (n.code()) {
            case PathNotice.SURVEYING -> {
                state = PathNotice.SURVEYING;
                stateAt = now;
                news = -1;
            }
            case PathNotice.PREVIEW -> {
                state = PathNotice.PREVIEW;
                length = n.a();
                tunnel = n.b();
                stakes = n.c();
                free = n.flag();
                stateAt = now;
                news = -1;
            }
            case PathNotice.BUILT, PathNotice.BUILDING -> {
                state = PathNotice.BUILT;
                length = n.code() == PathNotice.BUILDING ? n.a() : length;
                stateAt = now;
                news = n.code() == PathNotice.BUILDING ? n.code() : -1;
                newsA = n.a();
                newsAt = now;
            }
            case PathNotice.CLEAR -> {
                state = -1;
                news = -1;
            }
            case PathNotice.UNDONE, PathNotice.COMMITTED -> {
                state = -1;
                news(n, now);
            }
            default -> {
                if (n.code() >= PathNotice.DOOR_BOXED) state = -1;
                news(n, now);
            }
        }
    }

    private static void news(PathNotice n, long now) {
        news = n.code();
        newsA = n.a();
        newsFlag = n.flag();
        newsAt = now;
    }

    private enum Gesture { USE, PLACE, BREAK, SNEAK_USE }

    private record Chip(Gesture gesture, Component action) {}

    /** One drawn symbol of a gesture: a mouse with a button lit (mouse > 0), a key-cap (label) or the plus sign. */
    private record Glyph(int mouse, String label) {
        static final Glyph PLUS = new Glyph(0, "+");
    }

    private static boolean aiming(Minecraft mc) {
        if (mc.player == null) return false;
        if (mc.player.getMainHandItem().is(Stake.ITEM) || mc.player.getOffhandItem().is(Stake.ITEM)) return true;
        HitResult hit = mc.hitResult;
        return hit instanceof BlockHitResult b && mc.level != null && mc.level.getBlockState(b.getBlockPos()).is(Stake.BLOCK);
    }

    static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (state == PathNotice.PREVIEW && now - stateAt > PREVIEW_MS) state = -1;
        boolean fresh = news >= 0 && now - newsAt < (news >= PathNotice.DOOR_BOXED ? FAIL_MS : NEWS_MS);
        if (!fresh) news = -1;
        if (aiming(mc)) lookedAt = now;
        boolean watching = state >= 0 && now - lookedAt < GRACE_MS && now - stateAt > 0;
        boolean recent = state >= 0 && now - stateAt < NEWS_MS;
        if (!fresh && !watching && !recent) return;

        Component head;
        int accent;
        List<Chip> chips = new ArrayList<>();
        Component hint = null;
        long since;
        if (fresh) {
            since = now - newsAt;
            Component[] t = news(news, newsA, newsFlag);
            head = t[0];
            hint = t[1];
            accent = news >= PathNotice.DOOR_BOXED ? WARN : news == PathNotice.UNDONE || news == PathNotice.COMMITTED ? SKY : APRICOT;
            if (news == PathNotice.BUILDING) chips.addAll(chips());
        } else {
            since = now - Math.max(stateAt, lookedAt - GRACE_MS);
            accent = state == PathNotice.BUILT ? SAGE : APRICOT;
            if (state == PathNotice.SURVEYING) head = Component.translatable("countryroads.hud.surveying");
            else if (state == PathNotice.PREVIEW) {
                head = free ? Component.translatable("countryroads.hud.preview_free", stakes, length) : Component.translatable("countryroads.hud.preview", length);
                if (tunnel > 0) head = head.copy().append(Component.translatable("countryroads.hud.rock", tunnel));
            } else head = Component.translatable("countryroads.hud.built");
            chips.addAll(chips());
        }
        float alpha = (float) Math.min(1, since / (double) FADE_MS);
        if (fresh) alpha = Math.min(alpha, (float) Math.min(1, ((news >= PathNotice.DOOR_BOXED ? FAIL_MS : NEWS_MS) - since) / (double) FADE_MS));
        else if (!watching && !recent) alpha = 0;
        else if (!watching) alpha = Math.min(alpha, (float) Math.min(1, (NEWS_MS - (now - stateAt)) / (double) FADE_MS));
        if (alpha <= 0.02f) return;
        paint(g, mc.font, head, accent, chips, hint, alpha);
    }

    /** What the player can do with the path as it stands. */
    private static List<Chip> chips() {
        List<Chip> out = new ArrayList<>();
        if (state == PathNotice.PREVIEW) {
            out.add(new Chip(Gesture.USE, Component.translatable("countryroads.hud.build")));
            if (stakes > 0) out.add(new Chip(Gesture.SNEAK_USE, Component.translatable(free ? "countryroads.hud.join_road" : "countryroads.hud.end_here")));
            else out.add(new Chip(Gesture.PLACE, Component.translatable("countryroads.hud.add_stake")));
            out.add(new Chip(Gesture.BREAK, Component.translatable("countryroads.hud.cancel")));
        } else if (state == PathNotice.BUILT) {
            out.add(new Chip(Gesture.SNEAK_USE, Component.translatable("countryroads.hud.keep")));
            out.add(new Chip(Gesture.BREAK, Component.translatable("countryroads.hud.undo")));
        }
        return out;
    }

    /** Headline and optional hint of a piece of news. */
    private static Component[] news(int code, int a, boolean flag) {
        String k = switch (code) {
            case PathNotice.WAYPOINT -> flag ? "waypoint_end" : "waypoint";
            case PathNotice.END_ON -> "end_on";
            case PathNotice.END_OFF -> "end_off";
            case PathNotice.NEEDS_TWO -> "needs_two";
            case PathNotice.UNDONE -> "undone";
            case PathNotice.COMMITTED -> "committed";
            case PathNotice.NODROP -> "nodrop";
            case PathNotice.START_FAR -> "start_far";
            case PathNotice.BUILDING -> "building";
            case PathNotice.DOOR_BOXED -> "door_boxed";
            case PathNotice.BOXED_IN -> "boxed_in";
            case PathNotice.NO_ROUTE -> "no_route";
            case PathNotice.NO_BRIDGE -> "no_bridge";
            case PathNotice.NO_ROAD -> "no_road";
            case PathNotice.NO_WAYPOINT -> "no_waypoint";
            case PathNotice.ON_ROAD -> "on_road";
            default -> "disabled";
        };
        Component head = Component.translatable("countryroads.hud." + k, a);
        String hintKey = "countryroads.hud." + k + ".hint";
        return new Component[]{head, net.minecraft.locale.Language.getInstance().has(hintKey) ? Component.translatable(hintKey, a) : null};
    }

    private static Glyph glyph(KeyMapping m) {
        int[] buttons = {InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE};
        for (int i = 0; i < buttons.length; i++) {
            if (m.matches(InputConstants.Type.MOUSE.getOrCreate(buttons[i]))) return new Glyph(i + 1, "");
        }
        if (m.isUnbound()) return new Glyph(0, "-");
        for (int b = InputConstants.MOUSE_BUTTON_4; b <= InputConstants.MOUSE_BUTTON_8; b++) {
            if (m.matches(InputConstants.Type.MOUSE.getOrCreate(b))) return new Glyph(0, "M" + (b + 1));
        }
        String t = m.getTranslatedKeyMessage().getString();
        t = t.replace("Left ", "").replace("Right ", "R ").replace("Control", "Ctrl").replace("Escape", "Esc");
        if (t.length() > 6) t = t.substring(0, 6);
        return new Glyph(0, t);
    }

    private static List<Glyph> glyphs(Gesture g) {
        Minecraft mc = Minecraft.getInstance();
        return switch (g) {
            case USE, PLACE -> List.of(glyph(mc.options.keyUse));
            case BREAK -> List.of(glyph(mc.options.keyAttack));
            case SNEAK_USE -> List.of(glyph(mc.options.keyShift), Glyph.PLUS, glyph(mc.options.keyUse));
        };
    }

    private static final int MOUSE_W = 12, MOUSE_H = 14, GAP = 2;

    private static int glyphW(Font font, Glyph g) {
        if (g.mouse() > 0) return MOUSE_W;
        return g == Glyph.PLUS ? font.width("+") : font.width(g.label()) + 8;
    }

    private static int glyphsW(Font font, List<Glyph> gs) {
        int w = 0;
        for (Glyph g : gs) w += glyphW(font, g) + GAP;
        return w - GAP;
    }

    private static final net.minecraft.resources.Identifier MOUSE_LEFT = tex("mouse_left"), MOUSE_RIGHT = tex("mouse_right"),
            MOUSE_WHEEL = tex("mouse_wheel"), KEYCAP = tex("keycap");

    private static net.minecraft.resources.Identifier tex(String name) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("countryroads", "textures/gui/" + name + ".png");
    }

    private static void sprite(GuiGraphicsExtractor g, net.minecraft.resources.Identifier t, int x, int y, int w, int h, float alpha) {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, w, h, 16, 16, 16, 16, ARGB.color(alpha, 0xFFFFFF));
    }

    /** The shipped 16 x 16 mouse (2 empty columns each side) with the bound button lit; 12 of its columns are drawn. */
    private static void mouse(GuiGraphicsExtractor g, int x, int y, int button, float alpha) {
        sprite(g, button == 1 ? MOUSE_LEFT : button == 2 ? MOUSE_RIGHT : MOUSE_WHEEL, x - 2, y - 1, 16, 16, alpha);
    }

    /** A key cap of any width: the 16 x 16 sprite with its 7 pixel corners kept and the middle stretched. */
    private static void keycap(GuiGraphicsExtractor g, Font font, int x, int y, String label, float alpha) {
        int kw = font.width(label) + 8, h = 14, sl = 7, o = y - 1;
        int[] sx = {0, sl, 16 - sl}, sw = {sl, 16 - 2 * sl, sl};
        int[] dx = {x, x + sl, x + kw - sl}, dw = {sl, kw - 2 * sl, sl};
        int[] sy = {0, sl, 16 - sl}, sh = {sl, 16 - 2 * sl, sl};
        int[] dy = {o, o + sl, o + h - sl}, dh = {sl, h - 2 * sl, sl};
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                if (dw[i] <= 0 || dh[j] <= 0) continue;
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, KEYCAP, dx[i], dy[j], sx[i], sy[j], dw[i], dh[j], sw[i], sh[j], 16, 16, ARGB.color(alpha, 0xFFFFFF));
            }
        }
        g.text(font, label, x + 4, y + 1, ARGB.color(alpha, 0x1B1220), false);
    }

    /**
     * Draws the card: one panel, headline on top, one row per gesture (mouse / key-cap symbols from the player's own
     * bindings, then the action), the hint last. It sits on the right edge, vertically centred, at about three
     * quarters of the GUI scale rounded so text stays on whole pixels.
     */
    private static void paint(GuiGraphicsExtractor g, Font font, Component head, int accent, List<Chip> chips, Component hint, float alpha) {
        int w = g.guiWidth(), h = g.guiHeight();
        double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        float s = (float) (Math.max(1, Math.round(gui * 0.75)) / gui);
        int padX = 6, padY = 5, rowH = 16, headH = 12;
        int innerW = font.width(head);
        int keyW = 0;
        for (Chip c : chips) keyW = Math.max(keyW, glyphsW(font, glyphs(c.gesture())));
        for (Chip c : chips) innerW = Math.max(innerW, keyW + 5 + font.width(c.action()));
        if (hint != null) innerW = Math.max(innerW, font.width(hint));
        int cw = innerW + padX * 2;
        int ch = padY * 2 + headH + 2 + chips.size() * rowH + (hint != null ? 10 : 0) - 2;
        float sw = cw * s, sh = ch * s;
        int ax = Math.round(Math.max(2, w - sw - 6));
        int ay = Math.round(Math.max(2, Math.min(h - sh - 2, (h - sh) / 2f)));
        g.pose().pushMatrix();
        g.pose().translate(ax, ay);
        g.pose().scale(s, s);
        card(g, 0, 0, cw, ch, alpha, accent);
        int y = padY;
        g.text(font, head, padX, y + 1, ARGB.color(alpha, CREAM), false);
        g.fill(padX, y + 11, padX + 10, y + 12, ARGB.color(alpha, accent));
        y += headH + 3;
        for (Chip c : chips) {
            int x = padX + keyW - glyphsW(font, glyphs(c.gesture()));
            for (Glyph gl : glyphs(c.gesture())) {
                if (gl.mouse() > 0) mouse(g, x, y - 2, gl.mouse(), alpha);
                else if (gl == Glyph.PLUS) g.text(font, "+", x, y + 2, ARGB.color(alpha, DIM), false);
                else keycap(g, font, x, y, gl.label(), alpha);
                x += glyphW(font, gl) + GAP;
            }
            g.text(font, c.action(), padX + keyW + 5, y + 2, ARGB.color(alpha, CREAM), false);
            y += rowH;
        }
        if (hint != null) g.text(font, hint, padX, y - 1, ARGB.color(alpha, DIM), false);
        g.pose().popMatrix();
    }

    /** A tooltip-like card: plum plate, one pixel border in the accent colour, corners stepped by one pixel. */
    private static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha, int accent) {
        int edge = ARGB.color(alpha * 0.8f, accent), fill = ARGB.color(alpha * 0.92f, PLUM), inner = ARGB.color(alpha * 0.35f, APRICOT);
        g.fill(x + 1, y, x + w - 1, y + h, fill);
        g.fill(x, y + 1, x + w, y + h - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, edge);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + 1, y + h - 1, edge);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
        g.fill(x + 2, y + 1, x + w - 2, y + 2, inner);
        g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, inner);
        g.fill(x + 1, y + 2, x + 2, y + h - 2, inner);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, inner);
    }
}
