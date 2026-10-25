package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.questjournal.client.ClientJournal;
import com.questjournal.client.ClientNet;
import com.questjournal.client.ui.JournalScreen;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.DialogueChoice;
import com.questjournal.core.model.DialogueLine;
import com.questjournal.core.model.DialogueSequence;
import com.questjournal.core.model.LorePage;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.Severity;
import com.questjournal.core.validate.ValidationReport;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Field definitions for every editable type plus the editor actions. Adding a property to the quest model only needs
 * one more Field line here; the screens themselves are generic.
 */
final class Editors {
    private Editors() {
    }

    /** What the quest form needs to know about where the quest came from. */
    static final class Ctx {
        String pack = "server_quests";
        String file = "";
        String originalId = "";
        String originalJson = "";
        boolean existing;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static String clip(String s, int n) {
        String t = s.replace('\n', ' ');
        return t.length() <= n ? t : t.substring(0, n) + "...";
    }

    private static String uniqueId(String prefix, List<String> existing) {
        int n = existing.size() + 1;
        while (existing.contains(prefix + n)) {
            n++;
        }
        return prefix + n;
    }

    private static <T> void openForm(Screen back, String heading, T copy, List<Field<T>> fields, Consumer<T> save) {
        List<FormScreen.Action<T>> acts = List.of(
                new FormScreen.Action<T>("Done", s -> {
                    save.accept(copy);
                    mc().setScreen(back);
                }),
                new FormScreen.Action<T>("Cancel", s -> mc().setScreen(back)));
        mc().setScreen(new FormScreen<T>(back, heading, copy, fields, acts));
    }

    private static void text(Screen parent, String title, String initial, int max, Consumer<String> set) {
        mc().setScreen(new TextEditScreen(parent, title, initial, max, set));
    }

    // ================================================================== field lists

    static List<Field<ObjectiveDefinition>> objectiveFields() {
        List<Field<ObjectiveDefinition>> f = new ArrayList<>();
        Field.Of<ObjectiveDefinition> b = new Field.Of<>();
        f.add(b.text("Objective ID", o -> o.id, (o, v) -> o.id = v.trim(), 64));
        f.add(b.cycle("Type", o -> o.type, (o, v) -> {
            if (v.targetKind != o.type.targetKind) {
                o.target = "";
            }
            o.type = v;
        }));
        f.add(b.pick("Target", o -> o.target, (o, v) -> o.target = v, o -> o.type.targetKind));
        f.add(b.integer("Count", o -> o.count, (o, v) -> o.count = v, 1, 1_000_000).when(o -> o.type.usesCount));
        f.add(b.decimal("X", o -> o.x, (o, v) -> o.x = v).when(o -> o.type == ObjectiveType.VISIT_LOCATION));
        f.add(b.decimal("Y", o -> o.y, (o, v) -> o.y = v).when(o -> o.type == ObjectiveType.VISIT_LOCATION));
        f.add(b.decimal("Z", o -> o.z, (o, v) -> o.z = v).when(o -> o.type == ObjectiveType.VISIT_LOCATION));
        f.add(b.integer("Radius (blocks)", o -> o.radius, (o, v) -> o.radius = v, 1, 1_000_000).when(o -> o.type == ObjectiveType.VISIT_LOCATION));
        f.add(b.text("Description (optional)", o -> o.description, (o, v) -> o.description = v, 400));
        f.add(b.toggle("Required", o -> o.required, (o, v) -> o.required = v));
        f.add(b.toggle("Count before unlocked", o -> o.trackBeforeUnlock, (o, v) -> o.trackBeforeUnlock = v));
        return f;
    }

    static List<Field<RewardDefinition>> rewardFields() {
        List<Field<RewardDefinition>> f = new ArrayList<>();
        Field.Of<RewardDefinition> b = new Field.Of<>();
        f.add(b.text("Reward ID", r -> r.id, (r, v) -> r.id = v.trim(), 64));
        f.add(b.cycle("Type", r -> r.type, (r, v) -> r.type = v));
        f.add(b.pick("Item", r -> r.item, (r, v) -> r.item = v, r -> RegistryKind.ITEM).when(r -> r.type == RewardType.ITEM));
        f.add(b.integer("Count", r -> r.count, (r, v) -> r.count = v, 1, 100_000).when(r -> r.type == RewardType.ITEM));
        f.add(b.text("NBT (optional SNBT)", r -> r.nbt, (r, v) -> r.nbt = v, 4000).when(r -> r.type == RewardType.ITEM));
        f.add(b.integer("Amount", r -> r.amount, (r, v) -> r.amount = v, 1, 1_000_000).when(r -> r.type == RewardType.EXPERIENCE));
        f.add(b.cycle("Unit", r -> r.unit, (r, v) -> r.unit = v).when(r -> r.type == RewardType.EXPERIENCE));
        f.add(b.text("Command (runs on server)", r -> r.command, (r, v) -> r.command = v, 512).when(r -> r.type == RewardType.COMMAND));
        f.add(b.text("Shown to players", r -> r.label, (r, v) -> r.label = v, 200)
                .when(r -> r.type == RewardType.COMMAND || r.type == RewardType.CUSTOM));
        f.add(b.button("About command rewards", r -> "Read...", s -> mc().setScreen(new LinesScreen(s, "Command reward security", List.of(
                "A command reward only runs if ALL of these are true: command rewards are enabled in config/questjournal-common.toml "
                        + "(disabled by default), the command word is on the allowlist, the command passes the safety checks "
                        + "(no op/stop/execute..., no @a/@e selectors, no line breaks), and an operator approved this exact command.",
                "Allowed placeholders: {player} {uuid} {quest}. Anything else is refused.",
                "A command you publish yourself through this editor is approved on your behalf if it passes the policy. "
                        + "Commands in imported or hand-edited packs always need /questjournal approve <quest_id>.",
                "Players never see the command, only the text in \"Shown to players\"."))))
                .when(r -> r.type == RewardType.COMMAND));
        f.add(b.text("Provider id", r -> r.provider, (r, v) -> r.provider = v.trim(), 100).when(r -> r.type == RewardType.CUSTOM));
        return f;
    }

    static List<Field<LorePage>> loreFields() {
        List<Field<LorePage>> f = new ArrayList<>();
        Field.Of<LorePage> b = new Field.Of<>();
        f.add(b.text("Page title", p -> p.title, (p, v) -> p.title = v, 200));
        f.add(b.button("Text", p -> p.text.isEmpty() ? "Write..." : clip(p.text, 40), s -> text(s, "Lore page text", s.obj.text, 20000, v -> s.obj.text = v)));
        f.add(b.pick("Icon (item)", p -> p.icon, (p, v) -> p.icon = v, p -> RegistryKind.ITEM));
        return f;
    }

    static List<Field<DialogueChoice>> choiceFields() {
        List<Field<DialogueChoice>> f = new ArrayList<>();
        Field.Of<DialogueChoice> b = new Field.Of<>();
        f.add(b.text("Choice text", c -> c.text, (c, v) -> c.text = v, 300));
        f.add(b.integer("Next line (0-based, -1 ends)", c -> c.next, (c, v) -> c.next = v, -1, 2000));
        return f;
    }

    static List<Field<DialogueLine>> lineFields() {
        List<Field<DialogueLine>> f = new ArrayList<>();
        Field.Of<DialogueLine> b = new Field.Of<>();
        f.add(b.text("Speaker", l -> l.speaker, (l, v) -> l.speaker = v, 100));
        f.add(b.button("Text", l -> l.text.isEmpty() ? "Write..." : clip(l.text, 40), s -> text(s, "Dialogue line", s.obj.text, 4000, v -> s.obj.text = v)));
        f.add(b.button("Choices", l -> l.choices.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<DialogueChoice>(s, "Choices",
                s.obj.choices, c -> c.text + "  ->  " + (c.next < 0 ? "end" : "line " + c.next), DialogueChoice::new,
                DialogueChoice::copy, (back, copy, save) -> openForm(back, "Choice", copy, choiceFields(), save)))));
        return f;
    }

    static List<Field<DialogueSequence>> dialogueFields() {
        List<Field<DialogueSequence>> f = new ArrayList<>();
        Field.Of<DialogueSequence> b = new Field.Of<>();
        f.add(b.text("Dialogue ID", d -> d.id, (d, v) -> d.id = v.trim(), 64));
        f.add(b.text("Title", d -> d.title, (d, v) -> d.title = v, 200));
        f.add(b.cycle("Linked to", d -> d.gate, (d, v) -> d.gate = v));
        f.add(b.button("Lines", d -> d.lines.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<DialogueLine>(s, "Dialogue lines",
                s.obj.lines, l -> (l.speaker.isEmpty() ? "" : l.speaker + ": ") + clip(l.text, 70), DialogueLine::new,
                DialogueLine::copy, (back, copy, save) -> openForm(back, "Dialogue line", copy, lineFields(), save)))));
        return f;
    }

    static List<Field<QuestDefinition>> questFields(Ctx ctx) {
        List<Field<QuestDefinition>> f = new ArrayList<>();
        Field.Of<QuestDefinition> b = new Field.Of<>();
        f.add(b.text("Quest ID (stable)", q -> q.id, (q, v) -> q.id = v.trim(), 128));
        f.add(b.text("Title", q -> q.title, (q, v) -> q.title = v, 120));
        f.add(b.text("Short description", q -> q.shortDescription, (q, v) -> q.shortDescription = v, 300));
        f.add(b.button("Full description", q -> q.description.isEmpty() ? "Write..." : clip(q.description, 40),
                s -> text(s, "Quest description", s.obj.description, 20000, v -> s.obj.description = v)));
        f.add(b.cycle("Category", q -> q.category, (q, v) -> q.category = v));
        f.add(b.cycle("Acceptance", q -> q.acceptance, (q, v) -> q.acceptance = v));
        f.add(b.button("Prerequisites", q -> q.prerequisites.size() + " - edit...", Editors::prerequisites));
        f.add(b.cycle("Prerequisite mode", q -> q.prerequisiteMode, (q, v) -> q.prerequisiteMode = v).when(q -> q.prerequisites.size() > 1));
        f.add(b.cycle("Objectives are shown", q -> q.objectiveMode, (q, v) -> q.objectiveMode = v));
        f.add(b.cycle("When abandoned", q -> q.abandon, (q, v) -> q.abandon = v));
        f.add(b.cycle("Visibility", q -> q.visibility, (q, v) -> q.visibility = v));
        f.add(b.toggle("Enabled", q -> q.enabled, (q, v) -> q.enabled = v));
        f.add(b.integer("Sort order", q -> q.sortOrder, (q, v) -> q.sortOrder = v, -1_000_000, 1_000_000));
        f.add(b.pick("Icon (item)", q -> q.icon, (q, v) -> q.icon = v, q -> RegistryKind.ITEM));
        f.add(b.button("Objectives", q -> q.objectives.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<ObjectiveDefinition>(s,
                "Objectives", s.obj.objectives,
                o -> o.id + "   " + o.type.label() + "   " + o.target + (o.type.usesCount ? "  x" + o.count : "") + (o.required ? "" : "  (optional)"),
                () -> {
                    ObjectiveDefinition o = new ObjectiveDefinition();
                    List<String> ids = new ArrayList<>();
                    s.obj.objectives.forEach(x -> ids.add(x.id));
                    o.id = uniqueId("obj", ids);
                    return o;
                }, ObjectiveDefinition::copy, (back, copy, save) -> openForm(back, "Objective", copy, objectiveFields(), save)))));
        f.add(b.button("Rewards", q -> q.rewards.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<RewardDefinition>(s,
                "Rewards", s.obj.rewards,
                r -> r.id + "   " + r.type.label() + "   " + (r.type == RewardType.ITEM ? r.count + "x " + r.item : r.type == RewardType.EXPERIENCE
                        ? r.amount + " " + r.unit.label().toLowerCase() : r.type == RewardType.COMMAND ? "(command, hidden from players)" : r.provider),
                () -> {
                    RewardDefinition r = new RewardDefinition();
                    List<String> ids = new ArrayList<>();
                    s.obj.rewards.forEach(x -> ids.add(x.id));
                    r.id = uniqueId("r", ids);
                    return r;
                }, RewardDefinition::copy, (back, copy, save) -> openForm(back, "Reward", copy, rewardFields(), save)))));
        f.add(b.button("Lore pages", q -> q.lore.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<LorePage>(s, "Lore pages",
                s.obj.lore, p -> (p.title.isEmpty() ? "(untitled)" : p.title) + "   " + clip(p.text, 50), LorePage::new, LorePage::copy,
                (back, copy, save) -> openForm(back, "Lore page", copy, loreFields(), save)))));
        f.add(b.button("Dialogues", q -> q.dialogues.size() + " - edit...", s -> mc().setScreen(new ListEditorScreen<DialogueSequence>(s,
                "Dialogues", s.obj.dialogues, d -> d.id + "   " + d.title + "   (" + d.lines.size() + " lines, " + d.gate.label() + ")",
                () -> {
                    DialogueSequence d = new DialogueSequence();
                    List<String> ids = new ArrayList<>();
                    s.obj.dialogues.forEach(x -> ids.add(x.id));
                    d.id = uniqueId("talk", ids);
                    d.lines.add(new DialogueLine());
                    return d;
                }, DialogueSequence::copy, (back, copy, save) -> openForm(back, "Dialogue", copy, dialogueFields(), save)))));
        f.add(b.text("Pack (new quests only)", q -> ctx.pack, (q, v) -> ctx.pack = v.trim(), 64).when(q -> !ctx.existing));
        return f;
    }

    private static void prerequisites(FormScreen<QuestDefinition> s) {
        QuestDefinition q = s.obj;
        mc().setScreen(new ListEditorScreen<String>(s, "Prerequisite quests", q.prerequisites, id -> id + titleOf(id), () -> "", id -> id,
                (back, copy, save) -> mc().setScreen(new PickerScreen(back, "Choose prerequisite quest", PickerScreen.questEntries(q.id), copy, save, true))));
    }

    private static String titleOf(String id) {
        for (EditorSession.EQuest e : EditorSession.quests) {
            if (e.def != null && e.def.id.equals(id)) {
                return "   (" + e.def.title + ")";
            }
        }
        return "   (not found)";
    }

    // ================================================================== quest form + actions

    static void openQuest(Screen back, QuestDefinition def, Ctx ctx) {
        List<FormScreen.Action<QuestDefinition>> acts = List.of(
                new FormScreen.Action<QuestDefinition>("Validate", s -> validate(s)),
                new FormScreen.Action<QuestDefinition>("Preview", s -> mc().setScreen(new JournalScreen(s, ClientJournal.CQuest.preview(s.obj.copy(), QuestStatus.ACTIVE)))),
                new FormScreen.Action<QuestDefinition>("Save draft", s -> saveDraft(s, ctx)),
                new FormScreen.Action<QuestDefinition>("Publish", s -> publish(s, ctx)),
                new FormScreen.Action<QuestDefinition>("JSON", s -> json(s)),
                new FormScreen.Action<QuestDefinition>("Close", s -> s.onClose()));
        String head = (ctx.existing ? "Editing " : "New quest: ") + def.title + "  -  changes stay here until you Save draft or Publish";
        mc().setScreen(new FormScreen<QuestDefinition>(back, head, def, questFields(ctx), acts)
                .guard(() -> !QuestCodec.toJson(def, false).equals(ctx.originalJson)));
    }

    private static void validate(FormScreen<QuestDefinition> s) {
        QuestDefinition q = s.obj;
        List<String> lines = new ArrayList<>();
        QuestCodec.ParseResult pr = QuestCodec.parseValue(QuestCodec.toMap(q, false), "draft");
        for (Issue i : pr.issues()) {
            if (i.severity() != Severity.INFO) {
                lines.add(i.format());
            }
        }
        List<QuestDefinition> all = new ArrayList<>();
        for (EditorSession.EQuest e : EditorSession.quests) {
            if (e.def != null && !e.draft && !e.def.id.equals(q.id)) {
                all.add(e.def);
            }
        }
        all.add(q);
        ValidationReport rep = QuestValidator.validate(all, new ClientChecker(), null, null);
        for (Issue i : rep.forQuest(q.id)) {
            lines.add(i.format());
        }
        if (lines.isEmpty()) {
            lines.add("No problems found. (The server validates again when you publish.)");
        }
        mc().setScreen(new LinesScreen(s, "Validation: " + q.id, lines));
    }

    private static void saveDraft(FormScreen<QuestDefinition> s, Ctx ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "save_draft");
        m.put("quest", QuestCodec.toMap(s.obj, false));
        ClientNet.admin(m);
        ctx.originalJson = QuestCodec.toJson(s.obj, false);
        EditorSession.say(true, "Saving draft...");
    }

    private static void publish(FormScreen<QuestDefinition> s, Ctx ctx) {
        QuestDefinition q = s.obj;
        boolean force = "needs_force".equals(EditorSession.resultQuest) && !EditorSession.resultOk
                && Util.getMillis() - EditorSession.resultAt < 120_000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "publish");
        m.put("pack", ctx.pack);
        if (ctx.existing && q.id.equals(ctx.originalId)) {
            m.put("file", ctx.file);
        }
        m.put("quest", QuestCodec.toMap(q, false));
        if (force) {
            m.put("force", true);
        }
        ctx.originalJson = QuestCodec.toJson(q, false);
        EditorSession.say(true, force ? "Publishing anyway..." : "Publishing...");
        ClientNet.admin(m);
    }

    private static void json(FormScreen<QuestDefinition> s) {
        mc().setScreen(new TextEditScreen(s, "Quest JSON (advanced) - applied only if it parses", QuestCodec.toJson(s.obj, true), 600_000, text -> {
            QuestCodec.ParseResult pr = QuestCodec.parse(text, "editor");
            if (pr.ok()) {
                s.obj.assignFrom(pr.quest());
                EditorSession.say(true, "JSON applied to this draft (not published yet).");
            } else {
                String first = "unknown error";
                for (Issue i : pr.issues()) {
                    if (i.severity() == Severity.ERROR) {
                        first = i.path() + ": " + i.message();
                        break;
                    }
                }
                EditorSession.say(false, "The JSON was not applied: " + first);
            }
        }));
    }
}
