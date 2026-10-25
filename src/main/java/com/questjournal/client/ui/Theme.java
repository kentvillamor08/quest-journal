package com.questjournal.client.ui;

import com.questjournal.config.QJConfig;
import com.questjournal.core.model.QuestStatus;

/** Colors resolved once per screen from the client config (dark navy/charcoal with muted red and gold accents). */
public final class Theme {
    public final int bg;
    public final int panel;
    public final int panelLight;
    public final int accent;
    public final int gold;
    public final int text;
    public final int dim;
    public final int border;
    public final int ok = 0xFF5FB27A;
    public final int info = 0xFF5B9BD5;
    public final int bad;

    private Theme() {
        QJConfig.Client c = QJConfig.CLIENT;
        int alpha = (int) (Math.max(0.3, Math.min(1.0, c.backgroundOpacity.get())) * 255) << 24;
        bg = (QJConfig.argb(c.backgroundColor.get()) & 0x00FFFFFF) | alpha;
        panel = QJConfig.argb(c.panelColor.get());
        accent = QJConfig.argb(c.accentColor.get());
        gold = QJConfig.argb(c.goldColor.get());
        text = QJConfig.argb(c.textColor.get());
        panelLight = mix(panel, 0xFFFFFFFF, 0.08f);
        dim = mix(text, panel, 0.45f);
        border = mix(panel, gold, 0.35f);
        bad = mix(accent, 0xFFFFFFFF, 0.25f);
    }

    public static Theme load() {
        return new Theme();
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public int statusColor(QuestStatus s) {
        switch (s) {
            case AVAILABLE: return gold;
            case ACTIVE: return info;
            case CLAIMABLE: return ok;
            case COMPLETED: return mix(ok, panel, 0.5f);
            case ABANDONED: return mix(dim, 0xFF8A6D3B, 0.5f);
            case INVALID: return bad;
            default: return mix(dim, panel, 0.3f); // LOCKED
        }
    }

    public static String shortLabel(QuestStatus s) {
        switch (s) {
            case AVAILABLE: return "AVAILABLE";
            case ACTIVE: return "ACTIVE";
            case CLAIMABLE: return "CLAIM";
            case COMPLETED: return "DONE";
            case ABANDONED: return "ABANDONED";
            case INVALID: return "ATTENTION";
            default: return "LOCKED";
        }
    }
}
