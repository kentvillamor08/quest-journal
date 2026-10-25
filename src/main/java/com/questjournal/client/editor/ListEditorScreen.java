package com.questjournal.client.editor;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;
import com.questjournal.client.ui.UiList;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Generic "edit an ordered list" screen: add, edit, reorder and remove entries of any type. Edits apply to a copy until saved. */
public final class ListEditorScreen<T> extends Screen {
    public interface ItemEditor<T> {
        /** Open an editor for {@code copy}; call {@code save} if the user confirms. The editor must return to {@code back}. */
        void edit(Screen back, T copy, Consumer<T> save);
    }

    private final Screen parent;
    private final String heading;
    private final List<T> list;
    private final Function<T, String> summary;
    private final Supplier<T> factory;
    private final UnaryOperator<T> copier;
    private final ItemEditor<T> editor;
    private final UiList<T> ui = new UiList<>();
    private Theme t;

    public ListEditorScreen(Screen parent, String heading, List<T> list, Function<T, String> summary, Supplier<T> factory,
                            UnaryOperator<T> copier, ItemEditor<T> editor) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.list = list;
        this.summary = summary;
        this.factory = factory;
        this.copier = copier;
        this.editor = editor;
    }

    @Override
    protected void init() {
        t = Theme.load();
        String[] labels = {"Add", "Edit", "Up", "Down", "Remove", "Done"};
        int bw = Math.min(90, (width - 16) / labels.length - 4);
        int x = (width - labels.length * (bw + 4)) / 2;
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> act(which)).bounds(x, height - 24, bw, 18).build());
            x += bw + 4;
        }
    }

    private void act(int which) {
        int i = ui.selected;
        switch (which) {
            case 0:
                editor.edit(this, factory.get(), item -> list.add(item));
                break;
            case 1:
                if (i >= 0 && i < list.size()) {
                    editor.edit(this, copier.apply(list.get(i)), item -> list.set(i, item));
                }
                break;
            case 2:
                if (i > 0) {
                    Collections.swap(list, i, i - 1);
                    ui.selected = i - 1;
                }
                break;
            case 3:
                if (i >= 0 && i < list.size() - 1) {
                    Collections.swap(list, i, i + 1);
                    ui.selected = i + 1;
                }
                break;
            case 4:
                if (i >= 0 && i < list.size()) {
                    list.remove(i);
                    ui.selected = Math.min(i, list.size() - 1);
                }
                break;
            default:
                onClose();
        }
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
        g.drawString(font, heading + " (" + list.size() + ")", 10, 8, t.gold, false);
        ui.rowH = 20;
        ui.setBounds(10, 30, width - 20, height - 30 - 54);
        ui.setItems(list);
        Ui.rect(g, 10, 30, width - 20, height - 84, t.panel);
        ui.render(g, t, mx, my, true, (gg, item, i, x, y, w, h, hover, sel) -> {
            if (sel) {
                Ui.rect(gg, x, y, w, h - 1, t.panelLight);
            } else if (hover) {
                Ui.rect(gg, x, y, w, h - 1, Theme.mix(t.panel, t.panelLight, 0.5f));
            }
            gg.drawString(font, (i + 1) + ".", x + 4, y + 6, t.dim, false);
            gg.drawString(font, Ui.clip(font, summary.apply(item), w - 36), x + 26, y + 6, sel ? t.gold : t.text, false);
        });
        if (list.isEmpty()) {
            g.drawString(font, "Empty. Use Add to create the first entry.", 16, 40, t.dim, false);
        }
        EditorSession.drawStatus(g, font, t, 10, height - 50, width - 20);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (ui.mousePressed(mx, my)) {
                return true;
            }
            int i = ui.indexAt(mx, my);
            if (i >= 0) {
                ui.selected = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta) || super.mouseScrolled(mx, my, delta);
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
