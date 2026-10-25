package com.questjournal.client.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.questjournal.client.ClientJournal;
import com.questjournal.client.ClientJournal.CQuest;
import com.questjournal.client.ClientNet;
import com.questjournal.client.Keys;
import com.questjournal.client.editor.EditorScreen;
import com.questjournal.core.model.AbandonMode;
import com.questjournal.core.model.DialogueChoice;
import com.questjournal.core.model.DialogueGate;
import com.questjournal.core.model.DialogueLine;
import com.questjournal.core.model.DialogueSequence;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestCategory;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.model.RewardDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The full-screen quest journal: categories, status filters, search, sorting, a virtualized quest list and a detail panel.
 * It is only ever drawn while open (there is no HUD tracker) and uses plain fills, so it is cheap on the GPU.
 */
public final class JournalScreen extends Screen {
    private enum Filter {
        ALL("All"), AVAILABLE("Available"), ACTIVE("Active"), CLAIMABLE("Ready to claim"), COMPLETED("Completed"),
        ABANDONED("Abandoned"), ATTENTION("Attention"), LOCKED("Locked");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        boolean matches(CQuest q) {
            switch (this) {
                case ALL: return true;
                case ATTENTION: return q.status == QuestStatus.CLAIMABLE || q.status == QuestStatus.INVALID;
                default: return q.status.name().equals(name());
            }
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Sort {
        NEWEST("Newest"), OLDEST("Oldest"), PROGRESS("Progress");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Mode { QUEST, LORE, DIALOGUE }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final Screen parent;
    private final CQuest preview;
    private Theme t;
    private EditBox search;
    private QuestCategory category = QuestCategory.MAIN_STORY;
    private Filter filter = Filter.ALL;
    private Sort sort = Sort.NEWEST;
    private final UiList<CQuest> list = new UiList<>();
    private final List<CQuest> view = new ArrayList<>();
    private final int[] categoryCounts = new int[4];
    private final int[] filterCounts = new int[Filter.values().length];
    private int lastVersion = -1;
    private boolean viewDirty = true;
    private String selectedId;
    private float dScroll;
    private float dTarget;
    private int dContentH;
    private long lastFrame = Util.getMillis();
    private final List<Hit> hits = new ArrayList<>();
    private final Ui.WrapCache wrap = new Ui.WrapCache();
    private Mode mode = Mode.QUEST;
    private int lorePage;
    private DialogueSequence dialogue;
    private int dialogueLine;
    private int dialogueSteps;
    private ItemStack tipStack;
    private String tipText;
    private int top;
    private int bottom;
    private int lx;
    private int lw;
    private int cx;
    private int cw;
    private int dx;
    private int dw;
    private boolean compact;

    public JournalScreen(Screen parent) {
        super(Component.literal("Quest Journal"));
        this.parent = parent;
        this.preview = null;
    }

    /** Preview mode used by the editor: shows one quest exactly as players see it; nothing is sent to the server. */
    public JournalScreen(Screen parent, CQuest preview) {
        super(Component.literal("Quest Journal (preview)"));
        this.parent = parent;
        this.preview = preview;
        this.selectedId = preview.def.id;
        this.category = preview.def.category;
    }

    private boolean isPreview() {
        return preview != null;
    }

    @Override
    protected void init() {
        t = Theme.load();
        String keep = search == null ? "" : search.getValue();
        search = new EditBox(font, 0, 0, 120, 14, Component.literal("Search"));
        search.setMaxLength(100);
        search.setHint(Component.literal("Search..."));
        search.setValue(keep);
        search.setResponder(s -> viewDirty = true);
        addRenderableWidget(search);
        if (!isPreview() && !ClientJournal.I.synced) {
            ClientNet.action("sync", "", null, null);
        }
        viewDirty = true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // never pauses a (multiplayer or LAN) server
    }

    @Override
    public void tick() {
        search.tick();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ================================================================== data

    private CQuest selected() {
        if (isPreview()) {
            return preview;
        }
        return selectedId == null ? null : ClientJournal.I.quests.get(selectedId);
    }

    private void refreshView() {
        if (!viewDirty && lastVersion == ClientJournal.I.version) {
            return;
        }
        lastVersion = ClientJournal.I.version;
        viewDirty = false;
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        view.clear();
        java.util.Arrays.fill(categoryCounts, 0);
        java.util.Arrays.fill(filterCounts, 0);
        Iterable<CQuest> source = isPreview() ? List.of(preview) : ClientJournal.I.quests.values();
        for (CQuest c : source) {
            categoryCounts[c.def.category.ordinal()]++;
            if (!isPreview() && c.def.category != category) {
                continue;
            }
            filterCounts[Filter.ALL.ordinal()]++;
            for (Filter f : Filter.values()) {
                if (f != Filter.ALL && f.matches(c)) {
                    filterCounts[f.ordinal()]++;
                }
            }
            if (filter.matches(c) && (q.isEmpty() || c.haystack().contains(q))) {
                view.add(c);
            }
        }
        switch (sort) {
            case OLDEST:
                view.sort(Comparator.comparingLong((CQuest c) -> c.since == 0 ? Long.MAX_VALUE : c.since));
                break;
            case PROGRESS:
                view.sort(Comparator.comparingDouble(CQuest::progress).reversed());
                break;
            default:
                view.sort(Comparator.comparingLong((CQuest c) -> c.since).reversed());
        }
        list.setItems(view);
        list.selected = -1;
        for (int i = 0; i < view.size(); i++) {
            if (view.get(i).def.id.equals(selectedId)) {
                list.selected = i;
            }
        }
        if ((selectedId == null || !ClientJournal.I.quests.containsKey(selectedId)) && !view.isEmpty() && !isPreview()) {
            selectQuest(view.get(0));
        }
    }

    private void selectQuest(CQuest c) {
        if (!c.def.id.equals(selectedId)) {
            dScroll = 0;
            dTarget = 0;
            mode = Mode.QUEST;
        }
        selectedId = c.def.id;
        for (int i = 0; i < view.size(); i++) {
            if (view.get(i) == c) {
                list.selected = i;
            }
        }
    }

    // ================================================================== rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        tipStack = null;
        tipText = null;
        top = 30;
        bottom = height - 28;
        compact = width < 440;
        lx = 6;
        lw = compact ? 0 : Mth.clamp((int) (width * 0.15f), 86, 130);
        cx = compact ? 6 : lx + lw + 4;
        cw = compact ? Mth.clamp((int) (width * 0.42f), 110, 200) : Mth.clamp((int) (width * 0.30f), 130, 230);
        dx = cx + cw + 4;
        dw = width - 6 - dx;
        refreshView();

        Ui.rect(g, 0, 0, width, height, t.bg);
        drawHeader(g, mx, my);
        if (!compact) {
            drawLeft(g, mx, my);
        }
        drawList(g, mx, my);
        drawDetail(g, mx, my);
        drawFooter(g);
        super.render(g, mx, my, pt);
        if (tipStack != null) {
            g.renderTooltip(font, tipStack, mx, my);
        } else if (tipText != null) {
            g.renderTooltip(font, Component.literal(tipText), mx, my);
        }
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, boolean enabled, int frame, Runnable action) {
        boolean hover = enabled && Ui.inside(mx, my, x, y, w, h);
        Ui.rect(g, x, y, w, h, hover ? t.panelLight : t.panel);
        Ui.frame(g, x, y, w, h, enabled ? frame : t.dim);
        String s = Ui.clip(font, label, w - 6);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, enabled ? t.text : t.dim, false);
        if (enabled && action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private void drawHeader(GuiGraphics g, int mx, int my) {
        Ui.rect(g, 0, 0, width, 26, t.panel);
        Ui.rect(g, 0, 25, width, 1, t.border);
        Ui.rect(g, 0, 0, 4, 26, t.accent);
        int right = width - 6;
        if (ClientJournal.I.admin && !isPreview()) {
            right -= 52;
            button(g, mx, my, right, 5, 52, 16, "Editor", true, t.accent, () -> minecraft.setScreen(new EditorScreen(this)));
            right -= 4;
        }
        if (isPreview()) {
            right -= 120;
            button(g, mx, my, right, 5, 120, 16, "Status: " + preview.status.label, true, t.gold, this::cyclePreviewStatus);
            right -= 4;
        } else {
            right -= 86;
            button(g, mx, my, right, 5, 86, 16, "Sort: " + sort.label, true, t.border, () -> {
                sort = sort.next();
                viewDirty = true;
            });
            right -= 4;
        }
        right -= 124;
        search.setX(right);
        search.setY(6);
        search.setWidth(120);
        search.visible = !isPreview();
        String title = isPreview() ? "PREVIEW (nothing is saved)" : "QUEST JOURNAL";
        if (font.width(title) + 20 < right) {
            g.drawString(font, Component.literal(title).withStyle(ChatFormatting.BOLD), 12, 9, t.gold, false);
        }
    }

    private void cyclePreviewStatus() {
        QuestStatus[] order = {QuestStatus.LOCKED, QuestStatus.AVAILABLE, QuestStatus.ACTIVE, QuestStatus.CLAIMABLE,
                QuestStatus.COMPLETED, QuestStatus.ABANDONED, QuestStatus.INVALID};
        int i = 0;
        for (int k = 0; k < order.length; k++) {
            if (order[k] == preview.status) {
                i = k;
            }
        }
        CQuest next = CQuest.preview(preview.def, order[(i + 1) % order.length]);
        preview.status = next.status;
        preview.counts = next.counts;
        preview.done = next.done;
        preview.revealed = next.revealed;
        preview.problems = next.problems;
        mode = Mode.QUEST;
    }

    private void drawLeft(GuiGraphics g, int mx, int my) {
        Ui.rect(g, lx, top, lw, bottom - top, t.panel);
        int y = top + 4;
        g.drawString(font, "CATEGORIES", lx + 6, y, t.dim, false);
        y += 12;
        for (QuestCategory c : QuestCategory.values()) {
            final QuestCategory cat = c;
            boolean on = c == category;
            Ui.rect(g, lx + 2, y, lw - 4, 20, on ? t.panelLight : t.panel);
            if (on) {
                Ui.rect(g, lx + 2, y, 3, 20, t.accent);
            }
            g.drawString(font, Ui.clip(font, c.label(), lw - 34), lx + 10, y + 6, on ? t.gold : t.text, false);
            String n = String.valueOf(categoryCounts[c.ordinal()]);
            g.drawString(font, n, lx + lw - 8 - font.width(n), y + 6, t.dim, false);
            if (Ui.inside(mx, my, lx + 2, y, lw - 4, 20) && !on) {
                Ui.frame(g, lx + 2, y, lw - 4, 20, t.border);
            }
            hits.add(new Hit(lx + 2, y, lw - 4, 20, () -> {
                category = cat;
                selectedId = null;
                viewDirty = true;
            }));
            y += 22;
        }
        y += 6;
        g.drawString(font, "STATUS", lx + 6, y, t.dim, false);
        y += 12;
        for (Filter f : Filter.values()) {
            final Filter ff = f;
            boolean on = f == filter;
            Ui.rect(g, lx + 2, y, lw - 4, 14, on ? t.panelLight : t.panel);
            if (on) {
                Ui.rect(g, lx + 2, y, 3, 14, t.gold);
            }
            g.drawString(font, Ui.clip(font, f.label, lw - 34), lx + 10, y + 3, on ? t.gold : t.text, false);
            String n = String.valueOf(filterCounts[f.ordinal()]);
            g.drawString(font, n, lx + lw - 8 - font.width(n), y + 3, f == Filter.ATTENTION && filterCounts[f.ordinal()] > 0 ? t.bad : t.dim, false);
            hits.add(new Hit(lx + 2, y, lw - 4, 14, () -> {
                filter = ff;
                viewDirty = true;
            }));
            y += 15;
        }
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        Ui.rect(g, cx, top, cw, bottom - top, t.panel);
        int listTop = top;
        if (compact) {
            int half = cw / 2 - 2;
            button(g, mx, my, cx + 2, top + 2, half, 16, category.label(), true, t.border, () -> {
                category = QuestCategory.values()[(category.ordinal() + 1) % 4];
                selectedId = null;
                viewDirty = true;
            });
            button(g, mx, my, cx + cw / 2 + 1, top + 2, half, 16, filter.label, true, t.border, () -> {
                filter = filter.next();
                viewDirty = true;
            });
            listTop = top + 20;
        }
        list.rowH = 30;
        list.setBounds(cx, listTop, cw, bottom - listTop);
        list.render(g, t, mx, my, com.questjournal.config.QJConfig.CLIENT.smoothScrolling.get(), (gg, q, i, x, y, w, h, hover, sel) -> {
            if (sel) {
                Ui.rect(gg, x, y, w, h - 1, t.panelLight);
            } else if (hover) {
                Ui.rect(gg, x, y, w, h - 1, Theme.mix(t.panel, t.panelLight, 0.5f));
            }
            int sc = t.statusColor(q.status);
            Ui.rect(gg, x, y, 3, h - 1, sc);
            if (!q.def.icon.isEmpty()) {
                gg.renderItem(iconOf(q), x + 7, y + 6);
            }
            int tx = x + (q.def.icon.isEmpty() ? 9 : 28);
            String lbl = Theme.shortLabel(q.status);
            int pw = font.width(lbl) + 6;
            Ui.rect(gg, x + w - pw - 3, y + 3, pw, 11, Theme.mix(t.panel, sc, 0.28f));
            gg.drawString(font, lbl, x + w - pw, y + 5, sc, false);
            gg.drawString(font, Ui.clip(font, q.def.title, w - (tx - x) - pw - 8), tx, y + 4, sel ? t.gold : t.text, false);
            if (q.status == QuestStatus.ACTIVE) {
                Ui.bar(gg, tx, y + 19, w - (tx - x) - 8, 3, q.progress(), t.bg, t.info);
            } else {
                gg.drawString(font, Ui.clip(font, q.def.shortDescription, w - (tx - x) - 8), tx, y + 16, t.dim, false);
            }
        });
        if (view.isEmpty()) {
            String msg = !ClientJournal.I.synced && !isPreview() ? "Waiting for the server..." : "No quests match.";
            g.drawString(font, msg, cx + (cw - font.width(msg)) / 2, listTop + 20, t.dim, false);
        }
    }

    private ItemStack iconOf(CQuest q) {
        if (q.iconStack == null) {
            q.iconStack = stackFor(q.def.icon, 1);
        }
        return q.iconStack;
    }

    private static ItemStack stackFor(String id, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && ForgeRegistries.ITEMS.containsKey(rl)) {
            return new ItemStack(ForgeRegistries.ITEMS.getValue(rl), Math.max(1, Math.min(count, 99)));
        }
        return new ItemStack(Items.BARRIER);
    }

    // ---------------------------------------------------------------- detail panel

    private void drawDetail(GuiGraphics g, int mx, int my) {
        Ui.rect(g, dx, top, dw, bottom - top, t.panel);
        Ui.frame(g, dx, top, dw, bottom - top, t.border);
        CQuest q = selected();
        if (q == null) {
            String msg = "Select a quest";
            g.drawString(font, msg, dx + (dw - font.width(msg)) / 2, top + 30, t.dim, false);
            return;
        }
        long now = Util.getMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float max = Math.max(0, dContentH - (bottom - top - 28));
        dTarget = Mth.clamp(dTarget, 0f, max);
        dScroll = com.questjournal.config.QJConfig.CLIENT.smoothScrolling.get()
                ? dScroll + (dTarget - dScroll) * Math.min(1f, dt * 16f) : dTarget;
        dScroll = Mth.clamp(dScroll, 0f, max);
        switch (mode) {
            case LORE: drawLore(g, mx, my, q); break;
            case DIALOGUE: drawDialogue(g, mx, my, q); break;
            default: drawQuest(g, mx, my, q);
        }
    }

    private int lines(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (FormattedCharSequence l : wrap.wrap(font, text, w)) {
            g.drawString(font, l, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private int measure(String text, int w) {
        return wrap.wrap(font, text, w).size() * 10;
    }

    private void drawQuest(GuiGraphics g, int mx, int my, CQuest q) {
        QuestDefinition d = q.def;
        int viewH = bottom - top - 28;
        int px = dx + 8;
        int pw = dw - 22;
        g.enableScissor(dx + 1, top + 1, dx + dw - 1, top + viewH);
        int y = top + 6 - (int) dScroll;
        int startY = y;
        y = lines(g, d.title, px, y, pw, t.gold) + 2;
        int sc = t.statusColor(q.status);
        String lbl = q.status.label.toUpperCase(Locale.ROOT);
        Ui.rect(g, px, y, font.width(lbl) + 8, 12, Theme.mix(t.panel, sc, 0.3f));
        g.drawString(font, lbl, px + 4, y + 2, sc, false);
        g.drawString(font, d.category.label(), px + font.width(lbl) + 14, y + 2, t.dim, false);
        y += 18;
        if (!d.objectives.isEmpty()) {
            Ui.bar(g, px, y, pw, 5, q.progress(), t.bg, q.status == QuestStatus.CLAIMABLE ? t.ok : t.info);
            String p = q.doneCount() + "/" + d.objectives.size() + " objectives";
            g.drawString(font, p, px, y + 8, t.dim, false);
            y += 22;
        }
        if (q.status == QuestStatus.INVALID) {
            int boxTop = y;
            y += 4;
            y = lines(g, "This quest needs attention from a server administrator. Nothing is lost; it works again once the problem is fixed:", px + 5, y, pw - 8, t.bad);
            for (String s : q.problems) {
                y = lines(g, "- " + s, px + 5, y + 2, pw - 8, t.text);
            }
            Ui.frame(g, px, boxTop, pw, y + 4 - boxTop, t.bad);
            y += 10;
        }
        if (q.status == QuestStatus.LOCKED && !d.prerequisites.isEmpty()) {
            StringBuilder sb = new StringBuilder(d.prerequisiteMode == com.questjournal.core.model.PrerequisiteMode.ANY ? "Requires any of: " : "Requires: ");
            for (int i = 0; i < d.prerequisites.size(); i++) {
                CQuest pre = ClientJournal.I.quests.get(d.prerequisites.get(i));
                sb.append(i > 0 ? ", " : "").append(pre == null ? "???" : pre.def.title);
            }
            y = lines(g, sb.toString(), px, y, pw, t.gold) + 6;
        }
        if (!d.description.isEmpty() && q.status != QuestStatus.LOCKED) {
            y = lines(g, d.description, px, y, pw, t.text) + 8;
        } else if (!d.shortDescription.isEmpty()) {
            y = lines(g, d.shortDescription, px, y, pw, t.text) + 8;
        }
        if (!d.objectives.isEmpty() && q.status != QuestStatus.LOCKED) { // locked quests do not spoil their objectives
            g.drawString(font, "OBJECTIVES", px, y, t.gold, false);
            y += 12;
            int hidden = 0;
            for (int i = 0; i < d.objectives.size(); i++) {
                if (!q.objectiveVisible(i)) {
                    hidden++;
                    continue;
                }
                y = drawObjective(g, mx, my, q, i, px, y, pw, viewH);
            }
            if (hidden > 0) {
                g.drawString(font, "+" + hidden + " more objective" + (hidden > 1 ? "s" : "") + " will be revealed as you progress", px, y, t.dim, false);
                y += 14;
            }
            y += 4;
        }
        if (q.status != QuestStatus.LOCKED) {
            y = drawExtras(g, mx, my, q, px, y, pw, viewH);
        }
        if (!d.rewards.isEmpty() && q.status != QuestStatus.LOCKED) {
            y = drawRewards(g, mx, my, q, px, y, pw, viewH);
        }
        dContentH = y - startY + 12;
        g.disableScissor();
        if (dContentH > viewH) {
            int bh = Math.max(14, viewH * viewH / dContentH);
            int by = top + (int) ((viewH - bh) * (dScroll / Math.max(1, dContentH - viewH)));
            Ui.rect(g, dx + dw - 6, top + 1, 4, viewH, t.panelLight);
            Ui.rect(g, dx + dw - 6, by, 4, bh, t.gold);
        }
        drawActions(g, mx, my, q);
    }

    private boolean fullyVisible(int y, int h, int viewH) {
        return y >= top && y + h <= top + viewH;
    }

    private int drawObjective(GuiGraphics g, int mx, int my, CQuest q, int i, int px, int y, int pw, int viewH) {
        ObjectiveDefinition o = q.def.objectives.get(i);
        boolean done = q.done[i];
        Ui.frame(g, px, y + 1, 9, 9, done ? t.ok : t.border);
        if (done) {
            Ui.rect(g, px + 2, y + 3, 5, 5, t.ok);
        }
        int req = o.requiredCount();
        String counter = req > 1 ? Math.min(q.counts[i], req) + "/" + req : "";
        int tw = pw - 16 - (counter.isEmpty() ? 0 : font.width(counter) + 6);
        String text = Names.describe(o) + (o.required ? "" : " (optional)");
        int y2 = lines(g, text, px + 14, y + 1, tw, done ? t.dim : t.text);
        if (!counter.isEmpty()) {
            g.drawString(font, counter, px + pw - font.width(counter), y + 1, done ? t.ok : t.gold, false);
        }
        y = Math.max(y + 12, y2 + 1);
        if (req > 1 && !done) {
            Ui.bar(g, px + 14, y, pw - 14, 3, q.counts[i] / (float) req, t.bg, t.info);
            y += 6;
        }
        if (o.type == ObjectiveType.DELIVER_ITEM && q.status == QuestStatus.ACTIVE && !done) {
            final String qid = q.def.id;
            final String oid = o.id;
            if (fullyVisible(y, 14, viewH)) {
                button(g, mx, my, px + 14, y, 92, 14, "Submit items", !isPreview(), t.gold, () -> ClientNet.action("submit", qid, oid, null));
            }
            y += 18;
        }
        return y + 3;
    }

    private int drawExtras(GuiGraphics g, int mx, int my, CQuest q, int px, int y, int pw, int viewH) {
        QuestDefinition d = q.def;
        if (!d.lore.isEmpty()) {
            if (fullyVisible(y, 16, viewH)) {
                button(g, mx, my, px, y, 130, 16, "Lore (" + d.lore.size() + (d.lore.size() == 1 ? " page)" : " pages)"), true, t.border, () -> {
                    mode = Mode.LORE;
                    lorePage = 0;
                    dTarget = 0;
                });
            }
            y += 20;
        }
        for (DialogueSequence ds : d.dialogues) {
            boolean read = q.dialoguesDone.contains(ds.id);
            String tag = read ? " (read)" : ds.gate == DialogueGate.NONE ? "" : ds.gate == DialogueGate.ACCEPT ? " (required to accept)" : " (required to claim)";
            final DialogueSequence seq = ds;
            if (fullyVisible(y, 16, viewH)) {
                button(g, mx, my, px, y, Math.min(pw, 230), 16, "Talk: " + (ds.title.isEmpty() ? ds.id : ds.title) + tag, true,
                        read || ds.gate == DialogueGate.NONE ? t.border : t.gold, () -> {
                            mode = Mode.DIALOGUE;
                            dialogue = seq;
                            dialogueLine = 0;
                            dialogueSteps = 0;
                            dTarget = 0;
                        });
            }
            y += 20;
        }
        return y + 4;
    }

    private int drawRewards(GuiGraphics g, int mx, int my, CQuest q, int px, int y, int pw, int viewH) {
        QuestDefinition d = q.def;
        if (q.rewardStacks == null) {
            q.rewardStacks = new ArrayList<>();
            for (RewardDefinition r : d.rewards) {
                q.rewardStacks.add(r.type == com.questjournal.core.model.RewardType.ITEM ? stackFor(r.item, r.count) : ItemStack.EMPTY);
            }
        }
        g.drawString(font, "REWARDS", px, y, t.gold, false);
        y += 12;
        int x = px;
        for (int i = 0; i < d.rewards.size(); i++) {
            RewardDefinition r = d.rewards.get(i);
            if (r.type == com.questjournal.core.model.RewardType.ITEM) {
                ItemStack s = q.rewardStacks.get(i);
                if (x + 20 > px + pw) {
                    x = px;
                    y += 20;
                }
                Ui.frame(g, x, y, 18, 18, t.border);
                g.renderItem(s, x + 1, y + 1);
                g.renderItemDecorations(font, s, x + 1, y + 1);
                if (Ui.inside(mx, my, x, y, 18, 18) && my >= top && my < top + viewH) {
                    tipStack = s;
                    if (r.count > s.getCount()) {
                        tipText = r.count + "x " + Names.name(RegistryKind.ITEM, r.item);
                        tipStack = null;
                    }
                }
                x += 20;
            }
        }
        if (x != px) {
            y += 22;
        }
        for (RewardDefinition r : d.rewards) {
            switch (r.type) {
                case EXPERIENCE:
                    g.drawString(font, "+" + r.amount + (r.unit == com.questjournal.core.model.XpUnit.LEVELS ? " experience levels" : " experience"), px, y, t.ok, false);
                    y += 11;
                    break;
                case COMMAND:
                case CUSTOM:
                    g.drawString(font, Ui.clip(font, r.label.isEmpty() ? "Special reward" : r.label, pw), px, y, t.ok, false);
                    y += 11;
                    break;
                default:
                    break;
            }
        }
        return y + 6;
    }

    private void drawActions(GuiGraphics g, int mx, int my, CQuest q) {
        int y = bottom - 24;
        Ui.rect(g, dx + 1, y - 2, dw - 2, 25, t.panel);
        Ui.rect(g, dx + 1, y - 2, dw - 2, 1, t.border);
        final String id = q.def.id;
        int x = dx + 8;
        boolean live = !isPreview();
        if (q.status == QuestStatus.AVAILABLE || q.status == QuestStatus.ABANDONED) {
            button(g, mx, my, x, y + 2, 110, 18, q.status == QuestStatus.ABANDONED ? "Accept again" : "Accept quest", live, t.gold,
                    () -> ClientNet.action("accept", id, null, null));
            x += 116;
        }
        if (q.status == QuestStatus.ACTIVE && q.def.abandon != AbandonMode.DISABLED) {
            button(g, mx, my, x, y + 2, 90, 18, "Abandon", live, t.accent, () -> askAbandon(q));
            x += 96;
        }
        if (q.status == QuestStatus.CLAIMABLE) {
            button(g, mx, my, x, y + 2, 120, 18, "Claim reward", live, t.ok, () -> ClientNet.action("claim", id, null, null));
        }
    }

    private void askAbandon(CQuest q) {
        boolean progress = false;
        for (int c : q.counts) {
            progress |= c > 0;
        }
        final String id = q.def.id;
        if (q.def.abandon == AbandonMode.RESET_PROGRESS && progress) {
            minecraft.setScreen(new ConfirmScreen(yes -> {
                minecraft.setScreen(this);
                if (yes) {
                    ClientNet.action("abandon", id, null, null);
                }
            }, Component.literal("Abandon \"" + q.def.title + "\"?"),
                    Component.literal("Your progress on this quest will be reset when you accept it again.")));
        } else {
            ClientNet.action("abandon", id, null, null);
        }
    }

    // ---------------------------------------------------------------- lore and dialogue viewers

    private void backButton(GuiGraphics g, int mx, int my, int x, int y) {
        button(g, mx, my, x, y, 70, 18, "Back", true, t.border, () -> {
            mode = Mode.QUEST;
            dTarget = 0;
        });
    }

    private void drawLore(GuiGraphics g, int mx, int my, CQuest q) {
        List<com.questjournal.core.model.LorePage> pages = q.def.lore;
        if (pages.isEmpty()) {
            mode = Mode.QUEST;
            return;
        }
        lorePage = Mth.clamp(lorePage, 0, pages.size() - 1);
        com.questjournal.core.model.LorePage p = pages.get(lorePage);
        int viewH = bottom - top - 28;
        int px = dx + 12;
        int pw = dw - 30;
        g.enableScissor(dx + 1, top + 1, dx + dw - 1, top + viewH);
        int y = top + 8 - (int) dScroll;
        int startY = y;
        if (!p.icon.isEmpty()) {
            g.renderItem(stackFor(p.icon, 1), px + pw - 16, y - 2);
        }
        y = lines(g, p.title.isEmpty() ? q.def.title : p.title, px, y, pw - 22, t.gold) + 6;
        Ui.rect(g, px, y, pw, 1, t.border);
        y += 8;
        y = lines(g, p.text, px, y, pw, t.text);
        dContentH = y - startY + 16;
        g.disableScissor();
        int fy = bottom - 22;
        backButton(g, mx, my, dx + 8, fy);
        button(g, mx, my, dx + dw - 150, fy, 70, 18, "< Previous", lorePage > 0, t.border, () -> {
            lorePage--;
            dTarget = 0;
        });
        button(g, mx, my, dx + dw - 76, fy, 70, 18, "Next >", lorePage < pages.size() - 1, t.border, () -> {
            lorePage++;
            dTarget = 0;
        });
        String pg = (lorePage + 1) + " / " + pages.size();
        g.drawString(font, pg, dx + dw - 160 - font.width(pg), fy + 5, t.dim, false);
    }

    private void drawDialogue(GuiGraphics g, int mx, int my, CQuest q) {
        if (dialogue == null || dialogue.lines.isEmpty()) {
            mode = Mode.QUEST;
            return;
        }
        dialogueLine = Mth.clamp(dialogueLine, 0, dialogue.lines.size() - 1);
        DialogueLine line = dialogue.lines.get(dialogueLine);
        int px = dx + 14;
        int pw = dw - 28;
        int y = top + 12;
        g.drawString(font, dialogue.title.isEmpty() ? dialogue.id : dialogue.title, px, y, t.dim, false);
        y += 16;
        Ui.rect(g, px - 6, y - 4, pw + 12, 2, t.accent);
        if (!line.speaker.isEmpty()) {
            g.drawString(font, Component.literal(line.speaker).withStyle(ChatFormatting.BOLD), px, y + 4, t.gold, false);
            y += 16;
        }
        y = lines(g, line.text, px, y + 4, pw, t.text) + 14;
        if (line.choices.isEmpty()) {
            boolean last = dialogueLine >= dialogue.lines.size() - 1;
            button(g, mx, my, px, y, 100, 18, last ? "Finish" : "Continue", true, t.gold, () -> advanceDialogue(q, last ? -1 : dialogueLine + 1));
        } else {
            for (DialogueChoice c : line.choices) {
                final int next = c.next;
                button(g, mx, my, px, y, pw, 18, "> " + c.text, true, t.gold, () -> advanceDialogue(q, next));
                y += 22;
            }
        }
        backButton(g, mx, my, dx + 8, bottom - 22);
    }

    private void advanceDialogue(CQuest q, int next) {
        if (next < 0 || next >= dialogue.lines.size() || ++dialogueSteps > 500) {
            if (!isPreview() && !q.dialoguesDone.contains(dialogue.id)) {
                ClientNet.action("dialogue", q.def.id, null, dialogue.id);
            }
            mode = Mode.QUEST;
            return;
        }
        dialogueLine = next;
    }

    private void drawFooter(GuiGraphics g) {
        ClientJournal j = ClientJournal.I;
        if (j.resultMsg.isEmpty() || Util.getMillis() - j.resultAt > 8000) {
            return;
        }
        int y = bottom + 3;
        int shown = 0;
        for (FormattedCharSequence l : wrap.wrap(font, j.resultMsg, width - 16)) {
            if (shown++ >= 2) {
                break;
            }
            g.drawString(font, l, 8, y, j.resultOk ? t.ok : t.bad, false);
            y += 10;
        }
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.inside(mx, my, h.x(), h.y(), h.w(), h.h())) {
                    search.setFocused(false);
                    h.action().run();
                    return true;
                }
            }
            if (list.mousePressed(mx, my)) {
                return true;
            }
            int idx = list.indexAt(mx, my);
            if (idx >= 0) {
                search.setFocused(false);
                selectQuest(view.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (list.mouseScrolled(mx, my, delta)) {
            return true;
        }
        if (Ui.inside(mx, my, dx, top, dw, bottom - top)) {
            dTarget = Math.max(0, dTarget - (float) delta * 26f);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx2, double dy2) {
        return list.mouseDragged(my) || super.mouseDragged(mx, my, button, dx2, dy2);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        list.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!search.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE && mode != Mode.QUEST) {
                mode = Mode.QUEST;
                return true;
            }
            if (Keys.OPEN_JOURNAL.matches(key, scan) && !isPreview()) {
                onClose();
                return true;
            }
            if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && !view.isEmpty()) {
                int i = Mth.clamp(list.selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, view.size() - 1);
                selectQuest(view.get(i));
                list.ensureVisible(i);
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
