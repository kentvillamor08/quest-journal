package com.questjournal.client.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/** Small immediate-mode drawing helpers (cheap fills only: no blur, no shaders). */
public final class Ui {
    private Ui() {
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    public static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int back, int fill) {
        rect(g, x, y, w, h, back);
        int fw = Math.round(w * Math.max(0f, Math.min(1f, frac)));
        if (fw > 0) {
            rect(g, x, y, fw, h, fill);
        }
    }

    public static String clip(Font f, String s, int width) {
        if (f.width(s) <= width) {
            return s;
        }
        return f.plainSubstrByWidth(s, Math.max(0, width - f.width("..."))) + "...";
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Word-wrap with a tiny cache so long quest texts are not re-wrapped every frame. */
    public static final class WrapCache {
        private final Map<String, List<FormattedCharSequence>> cache = new HashMap<>();
        private int width = -1;

        public List<FormattedCharSequence> wrap(Font f, String text, int w) {
            if (w != width) {
                cache.clear();
                width = w;
            }
            if (cache.size() > 400) {
                cache.clear();
            }
            return cache.computeIfAbsent(text, t -> f.split(FormattedText.of(t), Math.max(20, w)));
        }
    }
}
