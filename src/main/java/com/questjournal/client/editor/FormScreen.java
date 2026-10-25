package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Generic scrolling form: label + widget rows built from {@link Field}s, plus a row of action buttons. Widgets outside
 * the viewport are hidden and clipped, so forms of any length work at any GUI scale.
 */
public class FormScreen<T> extends Screen {
    public record Action<T>(String label, Consumer<FormScreen<T>> run) {
    }

    private record Row(String label, AbstractWidget widget) {
    }

    protected final Screen parent;
    public final T obj;
    private final List<Field<T>> fields;
    private final List<Action<T>> actions;
    private final String heading;
    private final List<Row> rows = new ArrayList<>();
    private float scroll;
    private Theme t;
    private Runnable onCloseHook;
    private java.util.function.BooleanSupplier dirty;

    public FormScreen(Screen parent, String heading, T obj, List<Field<T>> fields, List<Action<T>> actions) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.obj = obj;
        this.fields = fields;
        this.actions = actions;
    }

    public FormScreen<T> onClose(Runnable hook) {
        this.onCloseHook = hook;
        return this;
    }

    /** Ask for confirmation before leaving (Esc or Close) while {@code unsavedChanges} reports true. */
    public FormScreen<T> guard(java.util.function.BooleanSupplier unsavedChanges) {
        this.dirty = unsavedChanges;
        return this;
    }

    @Override
    protected void init() {
        t = Theme.load();
        rows.clear();
        clearWidgets();
        for (Field<T> f : fields) {
            if (f.visible.test(obj)) {
                AbstractWidget w = f.create(this, obj);
                rows.add(new Row(f.label, w));
                addWidget(w); // receives input; drawn manually inside the clipped viewport
            }
        }
        int n = actions.size();
        int bw = Math.min(110, (width - 16) / Math.max(1, n) - 4);
        int x = (width - n * (bw + 4)) / 2;
        for (Action<T> a : actions) {
            addRenderableWidget(Button.builder(Component.literal(a.label()), b -> a.run().accept(this))
                    .bounds(x, height - 24, bw, 18).build());
            x += bw + 4;
        }
    }

    /** Re-creates the rows (after a cycle/toggle changed which fields are visible) without losing the scroll position. */
    public void rebuild() {
        float keep = scroll;
        init();
        scroll = keep;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (dirty != null && dirty.getAsBoolean()) {
            minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> minecraft.setScreen(yes ? parent : this),
                    Component.literal("Discard changes?"),
                    Component.literal("You have edits that are neither published nor saved as a draft.")));
            return;
        }
        if (onCloseHook != null) {
            onCloseHook.run();
        }
        minecraft.setScreen(parent);
    }

    public Screen parentScreen() {
        return parent;
    }

    private int top() {
        return 30;
    }

    private int bottom() {
        return height - 52;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Ui.rect(g, 0, 0, width, height, t.bg);
        Ui.rect(g, 0, 0, width, 24, t.panel);
        Ui.rect(g, 0, 23, width, 1, t.border);
        Ui.rect(g, 0, 0, 4, 24, t.accent);
        g.drawString(font, Ui.clip(font, heading, width - 20), 10, 8, t.gold, false);

        int top = top();
        int bottom = bottom();
        int rowH = 22;
        int labelW = Mth.clamp(width / 3, 80, 170);
        int fx = 10 + labelW;
        int fw = width - fx - 14;
        int contentH = rows.size() * rowH;
        float max = Math.max(0, contentH - (bottom - top));
        scroll = Mth.clamp(scroll, 0f, max);
        Ui.rect(g, 6, top - 2, width - 12, bottom - top + 4, t.panel);
        g.enableScissor(6, top, width - 6, bottom);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = top + i * rowH - (int) scroll;
            boolean on = y + 18 > top && y < bottom;
            r.widget().visible = on;
            r.widget().active = on;
            r.widget().setX(fx);
            r.widget().setY(y + 2);
            r.widget().setWidth(fw);
            if (on) {
                g.drawString(font, Ui.clip(font, r.label(), labelW - 6), 12, y + 7, t.text, false);
                r.widget().render(g, mx, my, pt);
            }
        }
        g.disableScissor();
        if (max > 0) {
            int bh = Math.max(14, (bottom - top) * (bottom - top) / contentH);
            int by = top + (int) (((bottom - top) - bh) * (scroll / max));
            Ui.rect(g, width - 9, top, 3, bottom - top, t.panelLight);
            Ui.rect(g, width - 9, by, 3, bh, t.gold);
        }
        EditorSession.drawStatus(g, font, t, 8, bottom + 4, width - 16);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (float) delta * 22f;
        return true;
    }
}
