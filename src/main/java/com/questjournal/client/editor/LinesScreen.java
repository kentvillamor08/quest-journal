package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.List;

import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;
import com.questjournal.client.ui.UiList;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/** Read-only scrolling text (validation results). */
public final class LinesScreen extends Screen {
    private final Screen parent;
    private final String heading;
    private final List<String> source;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final UiList<FormattedCharSequence> ui = new UiList<>();
    private Theme t;

    public LinesScreen(Screen parent, String heading, List<String> lines) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.source = lines;
    }

    @Override
    protected void init() {
        t = Theme.load();
        lines.clear();
        for (String s : source) {
            lines.addAll(font.split(FormattedText.of(s), width - 40));
            lines.add(FormattedCharSequence.EMPTY);
        }
        ui.setItems(lines);
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 - 50, height - 24, 100, 18).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Ui.rect(g, 0, 0, width, height, t.bg);
        Ui.rect(g, 0, 0, width, 24, t.panel);
        Ui.rect(g, 0, 0, 4, 24, t.accent);
        g.drawString(font, heading, 10, 8, t.gold, false);
        ui.rowH = 10;
        ui.setBounds(10, 30, width - 20, height - 60);
        Ui.rect(g, 10, 30, width - 20, height - 60, t.panel);
        ui.render(g, t, mx, my, true, (gg, l, i, x, y, w, h, hover, sel) -> gg.drawString(font, l, x + 6, y, t.text, false));
        if (source.isEmpty()) {
            g.drawString(font, "Nothing to report.", 18, 40, t.ok, false);
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mousePressed(mx, my) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return ui.mouseDragged(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        ui.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }
}
