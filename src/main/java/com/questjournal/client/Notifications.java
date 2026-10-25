package com.questjournal.client;

import java.util.List;
import java.util.Map;

import com.questjournal.client.ui.Theme;
import com.questjournal.config.QJConfig;
import com.questjournal.core.json.Json;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;

/** Subtle, short-lived notifications using vanilla's toast system (top-right, stacked, auto-hiding). No HUD tracker. */
public final class Notifications {
    private Notifications() {
    }

    public static void show(List<Object> list) {
        if (list == null || !QJConfig.CLIENT.notificationsEnabled.get()) {
            return;
        }
        for (Object o : list) {
            Map<String, Object> m = Json.asObject(o);
            if (m == null) {
                continue;
            }
            String type = String.valueOf(m.get("t"));
            String title = String.valueOf(m.get("title"));
            long many = m.get("many") instanceof Number ? ((Number) m.get("many")).longValue() : 0;
            String head;
            String line;
            switch (type) {
                case "quest_available":
                    if (!QJConfig.CLIENT.notifyAvailable.get()) {
                        continue;
                    }
                    head = many > 0 ? many + " new quests available" : "New quest available";
                    line = many > 0 ? "Open the journal to see them" : title;
                    break;
                case "quest_started":
                    if (!QJConfig.CLIENT.notifyStarted.get()) {
                        continue;
                    }
                    head = many > 0 ? many + " quests started" : "Quest started";
                    line = many > 0 ? "Open the journal to see them" : title;
                    break;
                case "objective_progress":
                case "objective_complete":
                    if (!QJConfig.CLIENT.notifyProgress.get()) {
                        continue;
                    }
                    boolean done = type.equals("objective_complete");
                    head = done ? "Objective complete" : title;
                    line = String.valueOf(m.getOrDefault("obj", "")) + (done ? "" : "  " + m.get("n") + "/" + m.get("m"));
                    break;
                case "quest_claimable":
                    if (!QJConfig.CLIENT.notifyClaimable.get()) {
                        continue;
                    }
                    head = many > 0 ? many + " rewards ready" : "Ready to claim";
                    line = many > 0 ? "Open the journal to claim them" : title;
                    break;
                case "reward_claimed":
                    if (!QJConfig.CLIENT.notifyClaimed.get()) {
                        continue;
                    }
                    head = "Reward claimed";
                    line = title;
                    break;
                default:
                    continue;
            }
            QuestToast.addOrUpdate(Minecraft.getInstance().getToasts(), type + "|" + m.get("q") + "|" + m.get("obj"), head, line);
        }
    }

    /** Toast with the journal's colors. Re-using a token updates the visible toast instead of stacking duplicates. */
    public static final class QuestToast implements Toast {
        private final Object token;
        private String head;
        private String line;
        private long start;
        private boolean fresh = true;
        private final Theme theme = Theme.load();

        private QuestToast(Object token, String head, String line) {
            this.token = token;
            this.head = head;
            this.line = line;
        }

        public static void addOrUpdate(ToastComponent comp, Object token, String head, String line) {
            QuestToast existing = comp.getToast(QuestToast.class, token);
            if (existing == null) {
                comp.addToast(new QuestToast(token, head, line));
            } else {
                existing.head = head;
                existing.line = line;
                existing.fresh = true;
            }
        }

        @Override
        public Object getToken() {
            return token;
        }

        @Override
        public int width() {
            return 190;
        }

        @Override
        public int height() {
            return 28;
        }

        @Override
        public Toast.Visibility render(GuiGraphics g, ToastComponent comp, long time) {
            if (fresh) {
                start = time;
                fresh = false;
            }
            var font = Minecraft.getInstance().font;
            g.fill(0, 0, width(), height(), theme.panel);
            g.fill(0, 0, 3, height(), theme.accent);
            g.fill(0, 0, width(), 1, theme.border);
            g.fill(0, height() - 1, width(), height(), theme.border);
            g.drawString(font, com.questjournal.client.ui.Ui.clip(font, head, width() - 12), 8, 5, theme.gold, false);
            g.drawString(font, com.questjournal.client.ui.Ui.clip(font, line, width() - 12), 8, 16, theme.text, false);
            return time - start < QJConfig.CLIENT.notificationSeconds.get() * 1000L ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
        }
    }
}
