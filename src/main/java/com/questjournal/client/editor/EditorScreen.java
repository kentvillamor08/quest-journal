package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.questjournal.client.ClientJournal;
import com.questjournal.client.ClientNet;
import com.questjournal.client.ui.JournalScreen;
import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;
import com.questjournal.client.ui.UiList;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.QuestCategory;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.security.CommandPolicy;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.Severity;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Operator-only quest editor: browse every quest file on the server, create/edit/delete, preview as a player, validate,
 * save drafts and publish. Nothing here changes the live quests until Publish; the server re-checks the permission and
 * validates everything again. Opened from the journal's Editor button or /questjournal editor.
 */
public final class EditorScreen extends Screen {
    private static long lastRequest;

    private final Screen parent;
    private final UiList<EditorSession.EQuest> list = new UiList<>();
    private final List<EditorSession.EQuest> shown = new ArrayList<>();
    private final List<Button> needsSelection = new ArrayList<>();
    private EditBox search;
    private Theme t;
    private boolean viewDirty = true;
    private String lastQuery = "";

    public EditorScreen(Screen parent) {
        super(Component.literal("Quest Editor"));
        this.parent = parent;
    }

    public void dataChanged() {
        viewDirty = true;
        if (EditorSession.stale) {
            request();
        }
    }

    private void request() {
        long now = Util.getMillis();
        if (now - lastRequest > 1500) {
            lastRequest = now;
            EditorSession.stale = false;
            ClientNet.adminOp("open");
        }
    }

    @Override
    protected void init() {
        t = Theme.load();
        String keep = search == null ? "" : search.getValue();
        search = new EditBox(font, 10, 30, Math.max(80, width / 2 - 14), 16, Component.literal("Search"));
        search.setHint(Component.literal("Search quests..."));
        search.setValue(keep);
        search.setMaxLength(100);
        addRenderableWidget(search);
        needsSelection.clear();
        String[] names = {"New", "Edit", "Delete", "Preview", "Approve", "Reload", "Validate", "Issues", "Back"};
        int bw = 62;
        int perRow = Math.max(1, (width - 12) / (bw + 4));
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            int x = 8 + (i % perRow) * (bw + 4);
            int y = height - 24 - (names.length - 1) / perRow * 22 + (i / perRow) * 22;
            Button b = Button.builder(Component.literal(names[i]), btn -> act(which)).bounds(x, y, bw, 18).build();
            addRenderableWidget(b);
            if (i >= 1 && i <= 4) {
                needsSelection.add(b);
            }
        }
        if (!EditorSession.loaded || EditorSession.stale) {
            request();
        }
        viewDirty = true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        search.tick();
    }

    @Override
    public void onClose() {
        EditorSession.clear(); // releases the registry listings and the copy of the server's quest files
        minecraft.setScreen(parent);
    }

    private EditorSession.EQuest sel() {
        return list.selectedItem();
    }

    private void act(int which) {
        EditorSession.EQuest e = sel();
        switch (which) {
            case 0: newQuest(); break;
            case 1: if (e != null) edit(e); break;
            case 2: if (e != null) delete(e); break;
            case 3: if (e != null && e.def != null) {
                minecraft.setScreen(new JournalScreen(this, ClientJournal.CQuest.preview(e.def.copy(), QuestStatus.ACTIVE)));
            } break;
            case 4: if (e != null && e.def != null) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("op", "approve");
                m.put("id", e.def.id);
                ClientNet.admin(m);
            } break;
            case 5: ClientNet.adminOp("reload"); EditorSession.say(true, "Reloading..."); break;
            case 6: ClientNet.adminOp("validate"); EditorSession.say(true, "Validating (nothing is changed)..."); break;
            case 7: {
                List<String> lines = new ArrayList<>();
                for (Issue i : EditorSession.issues) {
                    lines.add(i.format());
                }
                minecraft.setScreen(new LinesScreen(this, "All issues from the last load (" + lines.size() + ")", lines));
                break;
            }
            default: onClose();
        }
    }

    private void newQuest() {
        QuestDefinition q = new QuestDefinition();
        List<String> ids = new ArrayList<>();
        EditorSession.quests.forEach(x -> ids.add(x.id()));
        int n = 1;
        while (ids.contains("server:new_quest_" + n)) {
            n++;
        }
        q.id = "server:new_quest_" + n;
        q.title = "New quest";
        q.category = QuestCategory.MAIN_STORY;
        Editors.Ctx ctx = new Editors.Ctx();
        Editors.openQuest(this, q, ctx);
    }

    private void edit(EditorSession.EQuest e) {
        Editors.Ctx ctx = new Editors.Ctx();
        ctx.existing = !e.draft;
        ctx.pack = e.draft || e.pack.isEmpty() ? "server_quests" : e.pack;
        ctx.file = e.draft ? "" : e.file;
        if (e.def != null) {
            QuestDefinition copy = e.def.copy();
            ctx.originalId = copy.id;
            ctx.originalJson = QuestCodec.toJson(copy, false);
            Editors.openQuest(this, copy, ctx);
            return;
        }
        // The file does not parse: let the operator fix the raw text, then continue in the form.
        minecraft.setScreen(new TextEditScreen(this, "Fix " + e.file + " (it currently cannot be loaded)", e.json, 600_000, text -> {
            QuestCodec.ParseResult pr = QuestCodec.parse(text, e.file);
            if (pr.ok()) {
                ctx.originalId = pr.quest().id;
                ctx.originalJson = "";
                Editors.openQuest(this, pr.quest(), ctx);
            } else {
                String first = pr.issues().isEmpty() ? "unknown error" : pr.issues().get(0).path() + ": " + pr.issues().get(0).message();
                EditorSession.say(false, "Still not valid: " + first);
            }
        }));
    }

    private void delete(EditorSession.EQuest e) {
        String id = e.id();
        minecraft.setScreen(new ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("op", e.draft ? "delete_draft" : "delete");
                m.put("id", e.draft ? e.file : id);
                ClientNet.admin(m);
            }
        }, Component.literal("Delete " + id + "?"),
                Component.literal(e.draft ? "The server-side draft will be removed." : "The file moves to the pack's .trash folder. Player progress is kept.")));
    }

    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (!viewDirty && q.equals(lastQuery)) {
            return;
        }
        viewDirty = false;
        lastQuery = q;
        String keepId = sel() == null ? null : sel().id();
        shown.clear();
        for (EditorSession.EQuest e : EditorSession.quests) {
            if (q.isEmpty() || (e.title() + " " + e.id() + " " + e.pack).toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(e);
            }
        }
        list.setItems(shown);
        list.selected = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id().equals(keepId)) {
                list.selected = i;
            }
        }
    }

    private boolean hasPendingCommand(QuestDefinition d) {
        for (RewardDefinition r : d.rewards) {
            if (r.type == RewardType.COMMAND && !EditorSession.approved.contains(CommandPolicy.fingerprint(d.id, r.id, r.command))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        refilter();
        Ui.rect(g, 0, 0, width, height, t.bg);
        Ui.rect(g, 0, 0, width, 24, t.panel);
        Ui.rect(g, 0, 0, 4, 24, t.accent);
        g.drawString(font, "QUEST EDITOR  (operators only)", 10, 8, t.gold, false);
        String stat = EditorSession.loaded ? EditorSession.quests.size() + " files" : "loading...";
        g.drawString(font, stat, width - 10 - font.width(stat), 8, t.dim, false);
        for (Button b : needsSelection) {
            b.active = sel() != null;
        }

        boolean wide = width >= 440;
        int rows = (width - 12) / 66;
        int toolbarH = rows <= 0 ? 22 : ((9 + Math.max(1, rows) - 1) / Math.max(1, rows)) * 22;
        int lw = wide ? width * 55 / 100 : width - 20;
        list.rowH = 26;
        list.setBounds(10, 50, lw, height - 50 - toolbarH - 22);
        Ui.rect(g, 10, 50, lw, height - 50 - toolbarH - 22, t.panel);
        list.render(g, t, mx, my, true, (gg, e, i, x, y, w, h, hover, sel) -> {
            if (sel) {
                Ui.rect(gg, x, y, w, h - 1, t.panelLight);
            } else if (hover) {
                Ui.rect(gg, x, y, w, h - 1, Theme.mix(t.panel, t.panelLight, 0.5f));
            }
            int right = x + w - 4;
            if (e.def == null) {
                right = badge(gg, right, y, "BROKEN", t.bad);
            } else {
                if (e.draft) {
                    right = badge(gg, right, y, "DRAFT", t.gold);
                }
                if (hasPendingCommand(e.def)) {
                    right = badge(gg, right, y, "CMD", t.gold);
                }
                for (Issue is : EditorSession.issuesFor(e.def.id)) {
                    if (is.severity() == Severity.ERROR) {
                        right = badge(gg, right, y, "!", t.bad);
                        break;
                    }
                }
            }
            gg.drawString(font, Ui.clip(font, e.title(), right - x - 12), x + 6, y + 4, sel ? t.gold : t.text, false);
            gg.drawString(font, Ui.clip(font, e.id() + (e.pack.isEmpty() ? "" : "   [" + e.pack + "]"), w - 12), x + 6, y + 14, t.dim, false);
        });
        if (EditorSession.loaded && shown.isEmpty()) {
            g.drawString(font, "No quests yet. Press New to create one.", 16, 60, t.dim, false);
        }
        if (wide) {
            drawDetails(g, 10 + lw + 6, 50, width - lw - 26, height - 50 - toolbarH - 22);
        }
        EditorSession.drawStatus(g, font, t, 10, height - toolbarH - 18, width - 20);
        super.render(g, mx, my, pt);
    }

    private int badge(GuiGraphics g, int right, int y, String text, int color) {
        int w = font.width(text) + 6;
        Ui.rect(g, right - w, y + 3, w, 11, Theme.mix(t.panel, color, 0.3f));
        g.drawString(font, text, right - w + 3, y + 5, color, false);
        return right - w - 3;
    }

    private void drawDetails(GuiGraphics g, int x, int y, int w, int h) {
        Ui.rect(g, x, y, w, h, t.panel);
        Ui.frame(g, x, y, w, h, t.border);
        EditorSession.EQuest e = sel();
        if (e == null) {
            g.drawString(font, "Select a quest", x + 8, y + 8, t.dim, false);
            return;
        }
        int ty = y + 6;
        for (net.minecraft.util.FormattedCharSequence l : font.split(net.minecraft.network.chat.FormattedText.of(e.title()), w - 16)) {
            g.drawString(font, l, x + 8, ty, t.gold, false);
            ty += 10;
        }
        g.drawString(font, Ui.clip(font, e.id(), w - 16), x + 8, ty, t.text, false);
        ty += 11;
        g.drawString(font, Ui.clip(font, (e.draft ? "Draft file: " : e.pack + "/") + e.file, w - 16), x + 8, ty, t.dim, false);
        ty += 14;
        List<String> notes = new ArrayList<>(e.problems);
        if (e.def != null) {
            for (Issue i : EditorSession.issuesFor(e.def.id)) {
                if (i.severity() != Severity.INFO) {
                    notes.add((i.blocking() ? "[blocks players] " : "") + i.path() + ": " + i.message());
                }
            }
            for (RewardDefinition r : e.def.rewards) {
                if (r.type == RewardType.COMMAND) {
                    boolean ok = EditorSession.approved.contains(CommandPolicy.fingerprint(e.def.id, r.id, r.command));
                    notes.add("Command reward " + r.id + ": " + (ok ? "approved" : "NOT approved (use Approve)")
                            + (EditorSession.commandsEnabled ? "" : "; command rewards are disabled in the server config"));
                }
            }
        }
        if (notes.isEmpty()) {
            g.drawString(font, "No problems reported.", x + 8, ty, t.ok, false);
            return;
        }
        for (String n : notes) {
            for (net.minecraft.util.FormattedCharSequence l : font.split(net.minecraft.network.chat.FormattedText.of("- " + n), w - 16)) {
                if (ty > y + h - 10) {
                    return;
                }
                g.drawString(font, l, x + 8, ty, t.text, false);
                ty += 10;
            }
            ty += 2;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (list.mousePressed(mx, my)) {
                return true;
            }
            int i = list.indexAt(mx, my);
            if (i >= 0) {
                list.selected = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return list.mouseScrolled(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return list.mouseDragged(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        list.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }
}
