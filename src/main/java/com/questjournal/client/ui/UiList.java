package com.questjournal.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Virtualized scrolling list: only the rows inside the viewport are painted, so lists with thousands of entries cost
 * the same as lists with ten. Fixed row height keeps the visible-range calculation O(1).
 */
public final class UiList<T> {
    public interface RowPainter<T> {
        void paint(GuiGraphics g, T item, int index, int x, int y, int w, int h, boolean hover, boolean selected);
    }

    public int x;
    public int y;
    public int w;
    public int h;
    public int rowH = 24;
    public int selected = -1;
    private List<T> items = new ArrayList<>();
    private float scroll;
    private float target;
    private long lastFrame = Util.getMillis();
    private boolean dragging;

    public void setItems(List<T> list) {
        items = list;
        clamp();
    }

    public List<T> items() {
        return items;
    }

    public T selectedItem() {
        return selected >= 0 && selected < items.size() ? items.get(selected) : null;
    }

    private float maxScroll() {
        return Math.max(0, items.size() * rowH - h);
    }

    private void clamp() {
        target = Mth.clamp(target, 0f, maxScroll());
        scroll = Mth.clamp(scroll, 0f, maxScroll());
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        clamp();
    }

    public void render(GuiGraphics g, Theme t, int mx, int my, boolean smooth, RowPainter<T> painter) {
        long now = Util.getMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (smooth) {
            scroll += (target - scroll) * Math.min(1f, dt * 16f);
            if (Math.abs(target - scroll) < 0.4f) {
                scroll = target;
            }
        } else {
            scroll = target;
        }
        g.enableScissor(x, y, x + w, y + h);
        int first = Math.max(0, (int) (scroll / rowH));
        int last = Math.min(items.size(), first + h / rowH + 2);
        for (int i = first; i < last; i++) {
            int ry = y + i * rowH - (int) scroll;
            boolean hover = Ui.inside(mx, my, x, Math.max(ry, y), w - 6, Math.min(rowH, y + h - ry));
            painter.paint(g, items.get(i), i, x, ry, w - 6, rowH, hover, i == selected);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int total = items.size() * rowH;
            int barH = Math.max(14, h * h / total);
            int barY = y + (int) ((h - barH) * (scroll / maxScroll()));
            Ui.rect(g, x + w - 4, y, 3, h, t.panelLight);
            Ui.rect(g, x + w - 4, barY, 3, barH, t.gold);
        }
    }

    public int indexAt(double mx, double my) {
        if (mx < x || mx >= x + w - 6 || my < y || my >= y + h) {
            return -1;
        }
        int i = (int) ((my - y + scroll) / rowH);
        return i >= 0 && i < items.size() ? i : -1;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!Ui.inside(mx, my, x, y, w, h)) {
            return false;
        }
        target = Mth.clamp(target - (float) delta * rowH, 0f, maxScroll());
        return true;
    }

    /** Starts a scrollbar drag if the press is on the scrollbar column. */
    public boolean mousePressed(double mx, double my) {
        if (maxScroll() > 0 && Ui.inside(mx, my, x + w - 8, y, 8, h)) {
            dragging = true;
            dragTo(my);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double my) {
        if (dragging) {
            dragTo(my);
        }
        return dragging;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private void dragTo(double my) {
        target = Mth.clamp((float) ((my - y) / h) * (items.size() * rowH), 0f, maxScroll());
    }

    public void ensureVisible(int index) {
        float top = index * rowH;
        if (top < target) {
            target = top;
        } else if (top + rowH > target + h) {
            target = top + rowH - h;
        }
        clamp();
    }
}
