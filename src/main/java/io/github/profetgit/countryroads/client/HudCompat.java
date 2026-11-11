package io.github.profetgit.countryroads.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The drawing calls whose shape differs between Minecraft versions (26.x GuiGraphicsExtractor, 1.21.1 GuiGraphics). */
final class HudCompat {
    private HudCompat() {}

    static int color(float alpha, int rgb) {
        //? if >=26.1 {
        return net.minecraft.util.ARGB.color(alpha, rgb);
        //?} else {
        /*return (Math.round(alpha * 255) << 24) | (rgb & 0xFFFFFF);
        *///?}
    }

    static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        //? if >=26.1 {
        g.text(font, s, x, y, color, false);
        //?} else {
        /*g.drawString(font, s, x, y, color, false);
        *///?}
    }

    static void text(GuiGraphicsExtractor g, Font font, Component c, int x, int y, int color) {
        //? if >=26.1 {
        g.text(font, c, x, y, color, false);
        //?} else {
        /*g.drawString(font, c, x, y, color, false);
        *///?}
    }

    /** Draws the (u, v, regionW, regionH) part of a 16 x 16 texture into the (x, y, w, h) box, tinted white with the alpha. */
    static void blit(GuiGraphicsExtractor g, Identifier t, int x, int y, int w, int h, int u, int v, int regionW, int regionH, float alpha) {
        //? if >=26.1 {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, t, x, y, u, v, w, h, regionW, regionH, 16, 16, color(alpha, 0xFFFFFF));
        //?} else {
        /*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(t, x, y, w, h, u, v, regionW, regionH, 16, 16);
        g.setColor(1f, 1f, 1f, 1f);
        *///?}
    }

    static void push(GuiGraphicsExtractor g, float x, float y, float scale) {
        //? if >=26.1 {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        //?} else {
        /*g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        *///?}
    }

    static void pop(GuiGraphicsExtractor g) {
        //? if >=26.1 {
        g.pose().popMatrix();
        //?} else {
        /*g.pose().popPose();
        *///?}
    }

    static boolean mouseBound(KeyMapping m, int button) {
        //? if >=26.1 {
        return m.matches(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(button));
        //?} else {
        /*return m.matchesMouse(button);
        *///?}
    }
}
