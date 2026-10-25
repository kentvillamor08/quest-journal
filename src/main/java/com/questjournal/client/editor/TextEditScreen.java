package com.questjournal.client.editor;

import java.util.function.Consumer;

import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-screen multi-line text editor (quest descriptions, lore pages, dialogue text, raw JSON). */
public final class TextEditScreen extends Screen {
    private final Screen parent;
    private final String heading;
    private final int maxLen;
    private final Consumer<String> onDone;
    private String text;
    private Theme t;

    public TextEditScreen(Screen parent, String heading, String initial, int maxLen, Consumer<String> onDone) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.text = initial;
        this.maxLen = maxLen;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        t = Theme.load();
        MultiLineEditBox box = new MultiLineEditBox(font, 10, 30, width - 20, height - 62, Component.literal("Type here..."), Component.literal(heading));
        box.setCharacterLimit(maxLen);
        box.setValue(text);
        box.setValueListener(v -> text = v);
        addRenderableWidget(box);
        setInitialFocus(box);
        int bw = Math.min(120, (width - 30) / 2);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            onDone.accept(text);
            minecraft.setScreen(parent);
        }).bounds(width / 2 - bw - 4, height - 24, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 4, height - 24, bw, 18).build());
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
        g.drawString(font, heading + "  (" + text.length() + "/" + maxLen + ")", 10, 8, t.gold, false);
        super.render(g, mx, my, pt);
    }
}
