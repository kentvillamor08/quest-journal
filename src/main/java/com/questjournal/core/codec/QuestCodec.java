package com.questjournal.core.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.core.model.AbandonMode;
import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.DialogueChoice;
import com.questjournal.core.model.DialogueGate;
import com.questjournal.core.model.DialogueLine;
import com.questjournal.core.model.DialogueSequence;
import com.questjournal.core.model.JsonEnum;
import com.questjournal.core.model.LorePage;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveMode;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.PrerequisiteMode;
import com.questjournal.core.model.QuestCategory;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.model.Visibility;
import com.questjournal.core.model.XpUnit;
import com.questjournal.core.util.Ids;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.Severity;

/**
 * Converts between JSON text and {@link QuestDefinition}. The same code path serves quest-pack files, the
 * in-game editor and the network sync, so a feature configured one way always works the other way too.
 * Parsing never throws for bad content: problems are returned as {@link Issue}s with a JSON path.
 */
public final class QuestCodec {
    public static final int CURRENT_SCHEMA = 1;

    // Per-quest safety limits (generous; there is deliberately NO limit on the number of quests).
    public static final int MAX_PREREQUISITES = 1000;
    public static final int MAX_OBJECTIVES = 1000;
    public static final int MAX_REWARDS = 200;
    public static final int MAX_LORE_PAGES = 500;
    public static final int MAX_DIALOGUES = 200;
    public static final int MAX_DIALOGUE_LINES = 2000;
    public static final int MAX_CHOICES = 16;
    public static final int MAX_COUNT = 1_000_000;

    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9_.:/-]{1,100}$");
    /** Placeholder text sent to clients instead of real command strings. */
    public static final String HIDDEN_COMMAND = "(hidden)";

    private static final Set<String> ROOT_KEYS = Set.of("schema_version", "id", "title", "short_description",
            "description", "category", "icon", "enabled", "sort_order", "visibility", "acceptance", "prerequisites",
            "prerequisite_mode", "objective_mode", "abandon", "objectives", "rewards", "lore", "dialogues");
    private static final Set<String> OBJECTIVE_KEYS = Set.of("id", "type", "target", "count", "description",
            "required", "track_before_unlock", "x", "y", "z", "radius");
    private static final Set<String> REWARD_KEYS = Set.of("id", "type", "item", "count", "nbt", "amount", "unit",
            "command", "label", "provider", "data");

    private QuestCodec() {
    }

    /** Result of parsing: {@code quest} is null whenever at least one ERROR was found. */
    public record ParseResult(QuestDefinition quest, List<Issue> issues) {
        public boolean ok() {
            return quest != null;
        }
    }

    public static ParseResult parse(String text, String file) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (JsonException e) {
            Ctx c = new Ctx(file);
            c.err("", "syntax_error", "Invalid JSON: " + e.getMessage());
            return c.result(null);
        }
        return parseValue(root, file);
    }

    // ================================================================== parsing

    public static ParseResult parseValue(Object root, String file) {
        Ctx c = new Ctx(file);
        Map<String, Object> m = Json.asObject(root);
        if (m == null) {
            c.err("", "not_an_object", "A quest file must contain a single JSON object.");
            return c.result(null);
        }
        if (m.containsKey("quests")) {
            c.err("quests", "multi_quest_file", "Files with a \"quests\" array are not supported; use one quest per file.");
            return c.result(null);
        }
        QuestDefinition q = new QuestDefinition();

        if (!m.containsKey("schema_version")) {
            c.warn("schema_version", "missing_schema_version", "No schema_version; assuming " + CURRENT_SCHEMA + ".");
        } else {
            int sv = c.integer(m, "schema_version", "schema_version", CURRENT_SCHEMA, 1, Integer.MAX_VALUE);
            if (sv > CURRENT_SCHEMA) {
                c.err("schema_version", "unsupported_schema_version", "Schema version " + sv
                        + " is newer than this mod supports (" + CURRENT_SCHEMA + "). Update Quest Journal.");
            }
        }

        q.id = c.str(m, "id", "id", Ids.MAX_QUEST_ID_LENGTH + 40, true);
        c.questId = q.id;
        if (!q.id.isEmpty() && !Ids.isQuestId(q.id)) {
            c.err("id", "bad_id", "Quest id '" + q.id + "' must look like namespace:path using lowercase letters, "
                    + "digits and _ . - (and / in the path), at most " + Ids.MAX_QUEST_ID_LENGTH + " characters.");
        }
        q.title = c.str(m, "title", "title", 120, true);
        if (m.containsKey("title") && q.title.trim().isEmpty()) {
            c.err("title", "empty_title", "The title must not be empty.");
        }
        q.shortDescription = c.str(m, "short_description", "short_description", 300, false);
        q.description = c.str(m, "description", "description", 20000, false);
        q.category = parseCategory(c, m);
        q.icon = c.resourceId(m, "icon", "icon", false);
        q.enabled = c.bool(m, "enabled", "enabled", true);
        q.sortOrder = c.integer(m, "sort_order", "sort_order", 0, -1_000_000, 1_000_000);
        q.visibility = c.enumVal(m, "visibility", "visibility", Visibility.class, Visibility.ALWAYS);
        q.acceptance = c.enumVal(m, "acceptance", "acceptance", AcceptanceMode.class, AcceptanceMode.MANUAL);
        q.prerequisiteMode = c.enumVal(m, "prerequisite_mode", "prerequisite_mode", PrerequisiteMode.class, PrerequisiteMode.ALL);
        q.objectiveMode = c.enumVal(m, "objective_mode", "objective_mode", ObjectiveMode.class, ObjectiveMode.ALL_AT_ONCE);
        q.abandon = c.enumVal(m, "abandon", "abandon", AbandonMode.class, AbandonMode.PRESERVE_PROGRESS);

        parsePrerequisites(c, m, q);
        parseObjectives(c, m, q);
        parseRewards(c, m, q);
        parseLore(c, m, q);
        parseDialogues(c, m, q);
        q.extra = c.extras(m, ROOT_KEYS, "");

        return c.result(q);
    }

    private static QuestCategory parseCategory(Ctx c, Map<String, Object> m) {
        Object v = m.get("category");
        if (v == null) {
            c.err("category", "missing_field", "'category' is required (" + JsonEnum.options(QuestCategory.class) + ").");
            return QuestCategory.MAIN_STORY;
        }
        if (!(v instanceof String)) {
            c.err("category", "wrong_type", "'category' must be a string.");
            return QuestCategory.MAIN_STORY;
        }
        String s = ((String) v).trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "main": s = "main_story"; break;
            case "side": s = "side_quests"; break;
            case "world": s = "world_quests"; break;
            case "boss": s = "boss_quests"; break;
            default: break;
        }
        QuestCategory cat = JsonEnum.parse(QuestCategory.class, s);
        if (cat == null) {
            c.err("category", "bad_value", "'" + v + "' is not a valid category (use one of: "
                    + JsonEnum.options(QuestCategory.class) + "). Quest Journal has exactly four categories.");
            return QuestCategory.MAIN_STORY;
        }
        return cat;
    }

    private static void parsePrerequisites(Ctx c, Map<String, Object> m, QuestDefinition q) {
        List<Object> list = c.list(m, "prerequisites", "prerequisites", MAX_PREREQUISITES);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "prerequisites[" + i + "]";
            Object o = list.get(i);
            if (!(o instanceof String) || !Ids.isQuestId((String) o)) {
                c.err(path, "bad_id", "Prerequisites must be quest ids like namespace:path.");
                continue;
            }
            String pre = (String) o;
            if (pre.equals(q.id)) {
                c.err(path, "self_prerequisite", "A quest cannot be its own prerequisite.");
            } else if (!seen.add(pre)) {
                c.warn(path, "duplicate_prerequisite", "Prerequisite '" + pre + "' is listed twice; the duplicate is ignored.");
            }
        }
        q.prerequisites = new ArrayList<>(seen);
    }

    private static void parseObjectives(Ctx c, Map<String, Object> m, QuestDefinition q) {
        List<Object> list = c.list(m, "objectives", "objectives", MAX_OBJECTIVES);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "objectives[" + i + "]";
            Map<String, Object> om = Json.asObject(list.get(i));
            if (om == null) {
                c.err(path, "not_an_object", "Each objective must be a JSON object.");
                continue;
            }
            ObjectiveDefinition o = new ObjectiveDefinition();
            o.id = c.str(om, "id", path + ".id", 64, true);
            if (!o.id.isEmpty() && !Ids.isLocalId(o.id)) {
                c.err(path + ".id", "bad_id", "Objective id '" + o.id + "' may only contain a-z, 0-9 and _ (max 64).");
            } else if (!o.id.isEmpty() && !ids.add(o.id)) {
                c.err(path + ".id", "duplicate_id", "Objective id '" + o.id + "' is used more than once in this quest.");
            }
            ObjectiveType type = c.enumVal(om, "type", path + ".type", ObjectiveType.class, null);
            if (type == null) {
                if (!om.containsKey("type")) {
                    c.err(path + ".type", "missing_field", "'type' is required (" + JsonEnum.options(ObjectiveType.class) + ").");
                }
                continue;
            }
            o.type = type;
            o.target = c.resourceId(om, "target", path + ".target", true);
            o.count = type.usesCount ? c.integer(om, "count", path + ".count", 1, 1, MAX_COUNT) : 1;
            o.description = c.str(om, "description", path + ".description", 400, false);
            o.required = c.bool(om, "required", path + ".required", true);
            o.trackBeforeUnlock = c.bool(om, "track_before_unlock", path + ".track_before_unlock", false);
            if (type == ObjectiveType.VISIT_LOCATION) {
                o.x = c.number(om, "x", path + ".x", 0, true);
                o.y = c.number(om, "y", path + ".y", 64, true);
                o.z = c.number(om, "z", path + ".z", 0, true);
                o.radius = c.integer(om, "radius", path + ".radius", 8, 1, 1_000_000);
            }
            o.extra = c.extras(om, OBJECTIVE_KEYS, path);
            q.objectives.add(o);
        }
    }

    private static void parseRewards(Ctx c, Map<String, Object> m, QuestDefinition q) {
        List<Object> list = c.list(m, "rewards", "rewards", MAX_REWARDS);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "rewards[" + i + "]";
            Map<String, Object> rm = Json.asObject(list.get(i));
            if (rm == null) {
                c.err(path, "not_an_object", "Each reward must be a JSON object.");
                continue;
            }
            RewardDefinition r = new RewardDefinition();
            r.id = rm.containsKey("id") ? c.str(rm, "id", path + ".id", 64, true) : "r" + (i + 1);
            if (!Ids.isLocalId(r.id)) {
                c.err(path + ".id", "bad_id", "Reward id '" + r.id + "' may only contain a-z, 0-9 and _ (max 64).");
            } else if (!ids.add(r.id)) {
                c.err(path + ".id", "duplicate_id", "Reward id '" + r.id + "' is used more than once in this quest.");
            }
            RewardType type = c.enumVal(rm, "type", path + ".type", RewardType.class, null);
            if (type == null) {
                if (!rm.containsKey("type")) {
                    c.err(path + ".type", "missing_field", "'type' is required (" + JsonEnum.options(RewardType.class) + ").");
                }
                continue;
            }
            r.type = type;
            switch (type) {
                case ITEM:
                    r.item = c.resourceId(rm, "item", path + ".item", true);
                    r.count = c.integer(rm, "count", path + ".count", 1, 1, 100_000);
                    r.nbt = c.str(rm, "nbt", path + ".nbt", 4000, false);
                    break;
                case EXPERIENCE:
                    r.amount = c.integer(rm, "amount", path + ".amount", 0, 1, 1_000_000);
                    if (!rm.containsKey("amount")) {
                        c.err(path + ".amount", "missing_field", "'amount' is required for experience rewards.");
                    }
                    r.unit = c.enumVal(rm, "unit", path + ".unit", XpUnit.class, XpUnit.POINTS);
                    break;
                case COMMAND:
                    r.command = c.str(rm, "command", path + ".command", 512, true);
                    r.label = c.str(rm, "label", path + ".label", 200, false);
                    break;
                case CUSTOM:
                    r.provider = c.str(rm, "provider", path + ".provider", 100, true);
                    if (!r.provider.isEmpty() && !PROVIDER.matcher(r.provider).matches()) {
                        c.err(path + ".provider", "bad_value", "Provider ids may only contain a-z, 0-9 and _ . : / -");
                    }
                    r.label = c.str(rm, "label", path + ".label", 200, false);
                    Map<String, Object> data = Json.asObject(rm.get("data"));
                    if (data != null) {
                        r.data = new LinkedHashMap<>(data);
                    } else if (rm.get("data") != null) {
                        c.err(path + ".data", "wrong_type", "'data' must be an object.");
                    }
                    break;
                default:
                    break;
            }
            r.extra = c.extras(rm, REWARD_KEYS, path);
            q.rewards.add(r);
        }
    }

    private static void parseLore(Ctx c, Map<String, Object> m, QuestDefinition q) {
        List<Object> list = c.list(m, "lore", "lore", MAX_LORE_PAGES);
        for (int i = 0; i < list.size(); i++) {
            String path = "lore[" + i + "]";
            Map<String, Object> pm = Json.asObject(list.get(i));
            if (pm == null) {
                c.err(path, "not_an_object", "Each lore page must be a JSON object.");
                continue;
            }
            LorePage p = new LorePage();
            p.title = c.str(pm, "title", path + ".title", 200, false);
            p.text = c.str(pm, "text", path + ".text", 20000, true);
            p.icon = c.resourceId(pm, "icon", path + ".icon", false);
            q.lore.add(p);
        }
    }

    private static void parseDialogues(Ctx c, Map<String, Object> m, QuestDefinition q) {
        List<Object> list = c.list(m, "dialogues", "dialogues", MAX_DIALOGUES);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "dialogues[" + i + "]";
            Map<String, Object> dm = Json.asObject(list.get(i));
            if (dm == null) {
                c.err(path, "not_an_object", "Each dialogue must be a JSON object.");
                continue;
            }
            DialogueSequence d = new DialogueSequence();
            d.id = c.str(dm, "id", path + ".id", 64, true);
            if (!d.id.isEmpty() && !Ids.isLocalId(d.id)) {
                c.err(path + ".id", "bad_id", "Dialogue id '" + d.id + "' may only contain a-z, 0-9 and _ (max 64).");
            } else if (!d.id.isEmpty() && !ids.add(d.id)) {
                c.err(path + ".id", "duplicate_id", "Dialogue id '" + d.id + "' is used more than once in this quest.");
            }
            d.title = c.str(dm, "title", path + ".title", 200, false);
            d.gate = c.enumVal(dm, "gate", path + ".gate", DialogueGate.class, DialogueGate.NONE);
            List<Object> lines = c.list(dm, "lines", path + ".lines", MAX_DIALOGUE_LINES);
            if (lines.isEmpty()) {
                c.err(path + ".lines", "empty_dialogue", "A dialogue needs at least one line.");
            }
            for (int j = 0; j < lines.size(); j++) {
                String lp = path + ".lines[" + j + "]";
                Map<String, Object> lm = Json.asObject(lines.get(j));
                if (lm == null) {
                    c.err(lp, "not_an_object", "Each dialogue line must be a JSON object.");
                    continue;
                }
                DialogueLine line = new DialogueLine();
                line.speaker = c.str(lm, "speaker", lp + ".speaker", 100, false);
                line.text = c.str(lm, "text", lp + ".text", 4000, true);
                List<Object> choices = c.list(lm, "choices", lp + ".choices", MAX_CHOICES);
                for (int k = 0; k < choices.size(); k++) {
                    String cp = lp + ".choices[" + k + "]";
                    Map<String, Object> cm = Json.asObject(choices.get(k));
                    if (cm == null) {
                        c.err(cp, "not_an_object", "Each choice must be a JSON object.");
                        continue;
                    }
                    DialogueChoice ch = new DialogueChoice();
                    ch.text = c.str(cm, "text", cp + ".text", 300, true);
                    ch.next = c.integer(cm, "next", cp + ".next", -1, -1, MAX_DIALOGUE_LINES);
                    if (ch.next >= lines.size()) {
                        c.err(cp + ".next", "bad_value", "'next' must be -1 (end) or the index of a line (0-" + (lines.size() - 1) + ").");
                    }
                    line.choices.add(ch);
                }
                d.lines.add(line);
            }
            q.dialogues.add(d);
        }
    }

    // ================================================================== writing

    /** Full definition as stored in pack files / sent to operators. */
    public static String toJson(QuestDefinition q, boolean pretty) {
        return Json.write(toMap(q, false), pretty);
    }

    /** Compact JSON for regular players: no command strings, no unknown fields. Cached per definition. */
    public static String clientJson(QuestDefinition q) {
        String cached = q.clientJsonCache;
        if (cached == null) {
            cached = Json.write(toMap(q, true), false);
            q.clientJsonCache = cached;
        }
        return cached;
    }

    public static Map<String, Object> toMap(QuestDefinition q, boolean forClient) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", (long) CURRENT_SCHEMA);
        m.put("id", q.id);
        m.put("title", q.title);
        m.put("short_description", q.shortDescription);
        m.put("description", q.description);
        m.put("category", q.category.json());
        if (!q.icon.isEmpty()) {
            m.put("icon", q.icon);
        }
        m.put("enabled", q.enabled);
        if (q.sortOrder != 0) {
            m.put("sort_order", (long) q.sortOrder);
        }
        m.put("visibility", q.visibility.json());
        m.put("acceptance", q.acceptance.json());
        m.put("prerequisites", new ArrayList<Object>(q.prerequisites));
        m.put("prerequisite_mode", q.prerequisiteMode.json());
        m.put("objective_mode", q.objectiveMode.json());
        m.put("abandon", q.abandon.json());

        List<Object> objs = new ArrayList<>();
        for (ObjectiveDefinition o : q.objectives) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", o.id);
            om.put("type", o.type.json());
            om.put("target", o.target);
            if (o.type.usesCount) {
                om.put("count", (long) o.count);
            }
            if (o.type == ObjectiveType.VISIT_LOCATION) {
                om.put("x", o.x);
                om.put("y", o.y);
                om.put("z", o.z);
                om.put("radius", (long) o.radius);
            }
            if (!o.description.isEmpty()) {
                om.put("description", o.description);
            }
            if (!o.required) {
                om.put("required", false);
            }
            if (o.trackBeforeUnlock) {
                om.put("track_before_unlock", true);
            }
            if (!forClient) {
                om.putAll(o.extra);
            }
            objs.add(om);
        }
        m.put("objectives", objs);

        List<Object> rews = new ArrayList<>();
        for (RewardDefinition r : q.rewards) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id);
            rm.put("type", r.type.json());
            switch (r.type) {
                case ITEM:
                    rm.put("item", r.item);
                    rm.put("count", (long) r.count);
                    if (!r.nbt.isEmpty()) {
                        rm.put("nbt", r.nbt);
                    }
                    break;
                case EXPERIENCE:
                    rm.put("amount", (long) r.amount);
                    rm.put("unit", r.unit.json());
                    break;
                case COMMAND:
                    rm.put("command", forClient ? HIDDEN_COMMAND : r.command);
                    if (!r.label.isEmpty()) {
                        rm.put("label", r.label);
                    }
                    break;
                case CUSTOM:
                    rm.put("provider", r.provider);
                    if (!r.label.isEmpty()) {
                        rm.put("label", r.label);
                    }
                    rm.put("data", forClient ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(r.data));
                    break;
                default:
                    break;
            }
            if (!forClient) {
                rm.putAll(r.extra);
            }
            rews.add(rm);
        }
        m.put("rewards", rews);

        List<Object> lore = new ArrayList<>();
        for (LorePage p : q.lore) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("title", p.title);
            pm.put("text", p.text);
            if (!p.icon.isEmpty()) {
                pm.put("icon", p.icon);
            }
            lore.add(pm);
        }
        m.put("lore", lore);

        List<Object> dialogues = new ArrayList<>();
        for (DialogueSequence d : q.dialogues) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id);
            dm.put("title", d.title);
            dm.put("gate", d.gate.json());
            List<Object> lines = new ArrayList<>();
            for (DialogueLine l : d.lines) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("speaker", l.speaker);
                lm.put("text", l.text);
                if (!l.choices.isEmpty()) {
                    List<Object> cs = new ArrayList<>();
                    for (DialogueChoice ch : l.choices) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("text", ch.text);
                        cm.put("next", (long) ch.next);
                        cs.add(cm);
                    }
                    lm.put("choices", cs);
                }
                lines.add(lm);
            }
            dm.put("lines", lines);
            dialogues.add(dm);
        }
        m.put("dialogues", dialogues);

        if (!forClient) {
            for (Map.Entry<String, Object> e : q.extra.entrySet()) {
                m.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    // ================================================================== parse context

    private static final class Ctx {
        final String file;
        String questId = "";
        final List<Issue> issues = new ArrayList<>();
        boolean hasError;

        Ctx(String file) {
            this.file = file == null ? "" : file;
        }

        void err(String path, String code, String msg) {
            hasError = true;
            issues.add(new Issue(Severity.ERROR, code, questId, file, path, msg, false));
        }

        void warn(String path, String code, String msg) {
            issues.add(new Issue(Severity.WARNING, code, questId, file, path, msg, false));
        }

        ParseResult result(QuestDefinition q) {
            return new ParseResult(hasError ? null : q, issues);
        }

        String str(Map<String, Object> m, String key, String path, int max, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) {
                    err(path, "missing_field", "'" + key + "' is required.");
                }
                return "";
            }
            if (!(v instanceof String)) {
                err(path, "wrong_type", "'" + key + "' must be a string.");
                return "";
            }
            String s = (String) v;
            if (s.length() > max) {
                err(path, "too_long", "'" + key + "' is longer than " + max + " characters.");
                return s.substring(0, max);
            }
            return s;
        }

        String resourceId(Map<String, Object> m, String key, String path, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) {
                    err(path, "missing_field", "'" + key + "' is required.");
                }
                return "";
            }
            if (!(v instanceof String)) {
                err(path, "wrong_type", "'" + key + "' must be a string like namespace:path.");
                return "";
            }
            String norm = Ids.normalizeResourceId((String) v);
            if (norm == null) {
                err(path, "bad_id", "'" + v + "' is not a valid registry id (expected namespace:path, lowercase).");
                return (String) v;
            }
            return norm;
        }

        boolean bool(Map<String, Object> m, String key, String path, boolean def) {
            Object v = m.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof Boolean)) {
                err(path, "wrong_type", "'" + key + "' must be true or false.");
                return def;
            }
            return (Boolean) v;
        }

        int integer(Map<String, Object> m, String key, String path, int def, int min, int max) {
            Object v = m.get(key);
            if (v == null) {
                return def;
            }
            long n;
            if (v instanceof Long) {
                n = (Long) v;
            } else if (v instanceof Double && ((Double) v) == Math.rint((Double) v)) {
                n = (long) (double) (Double) v;
            } else {
                err(path, "wrong_type", "'" + key + "' must be a whole number.");
                return def;
            }
            if (n < min || n > max) {
                err(path, "out_of_range", "'" + key + "' must be between " + min + " and " + max + " (was " + n + ").");
                return def;
            }
            return (int) n;
        }

        double number(Map<String, Object> m, String key, String path, double def, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) {
                    err(path, "missing_field", "'" + key + "' is required.");
                }
                return def;
            }
            if (!(v instanceof Number)) {
                err(path, "wrong_type", "'" + key + "' must be a number.");
                return def;
            }
            double d = ((Number) v).doubleValue();
            if (Math.abs(d) > 3.0e7) {
                err(path, "out_of_range", "'" + key + "' must be between -30000000 and 30000000.");
                return def;
            }
            return d;
        }

        <E extends Enum<E> & JsonEnum> E enumVal(Map<String, Object> m, String key, String path, Class<E> type, E def) {
            Object v = m.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof String)) {
                err(path, "wrong_type", "'" + key + "' must be a string.");
                return def;
            }
            E e = JsonEnum.parse(type, (String) v);
            if (e == null) {
                err(path, "bad_value", "'" + v + "' is not valid for '" + key + "' (use one of: " + JsonEnum.options(type) + ").");
                return def;
            }
            return e;
        }

        List<Object> list(Map<String, Object> m, String key, String path, int max) {
            Object v = m.get(key);
            if (v == null) {
                return new ArrayList<>();
            }
            List<Object> l = Json.asArray(v);
            if (l == null) {
                err(path, "wrong_type", "'" + key + "' must be an array.");
                return new ArrayList<>();
            }
            if (l.size() > max) {
                err(path, "too_many", "'" + key + "' has " + l.size() + " entries; the limit is " + max + ".");
                return new ArrayList<>(l.subList(0, max));
            }
            return l;
        }

        Map<String, Object> extras(Map<String, Object> m, Set<String> known, String path) {
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!known.contains(e.getKey())) {
                    extra.put(e.getKey(), e.getValue());
                    warn(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), "unknown_field",
                            "Unknown field '" + e.getKey() + "' is kept but ignored.");
                }
            }
            return extra;
        }
    }
}
