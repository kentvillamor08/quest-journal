package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.Severity;

import net.minecraft.Util;

/** The operator's view of the server's quest files, received when the editor opens. Cleared when the editor closes. */
public final class EditorSession {
    /** One quest file (or server-side draft). {@code def} is null when the file does not parse; {@code json} is always the raw text. */
    public static final class EQuest {
        public String pack = "";
        public String file = "";
        public String json = "";
        public QuestDefinition def;
        public boolean draft;
        public List<String> problems = new ArrayList<>();

        public String title() {
            return def != null ? def.title : "(broken file) " + file;
        }

        public String id() {
            return def != null ? def.id : file;
        }
    }

    public static final List<EQuest> quests = new ArrayList<>();
    public static final List<String> packs = new ArrayList<>();
    public static final List<String> dimensions = new ArrayList<>();
    public static final List<Issue> issues = new ArrayList<>();
    public static final Set<String> approved = new HashSet<>();
    public static boolean commandsEnabled;
    public static boolean loaded;
    /** Set after a successful publish/delete/reload so the editor list is refreshed from the server. */
    public static boolean stale;
    public static boolean resultOk;
    public static String resultText = "";
    public static String resultQuest = "";
    public static long resultAt;

    private EditorSession() {
    }

    public static void clear() {
        quests.clear();
        packs.clear();
        dimensions.clear();
        issues.clear();
        approved.clear();
        loaded = false;
        stale = false;
        resultText = "";
        com.questjournal.client.editor.PickerScreen.clearCaches();
    }

    /** Local status line (not a server reply, so it does not trigger a refresh). */
    public static void say(boolean ok, String text) {
        resultOk = ok;
        resultText = text;
        resultQuest = "";
        resultAt = Util.getMillis();
    }

    public static void onResult(boolean ok, String text, String questId) {
        resultOk = ok;
        resultText = text;
        resultQuest = questId;
        resultAt = Util.getMillis();
        if (ok) {
            stale = true;
        }
    }

    public static void onData(Map<String, Object> m) {
        quests.clear();
        packs.clear();
        dimensions.clear();
        issues.clear();
        approved.clear();
        for (Object o : list(m, "packs")) {
            Map<String, Object> p = Json.asObject(o);
            if (p != null) {
                packs.add(String.valueOf(p.get("id")));
            }
        }
        for (Object o : list(m, "quests")) {
            Map<String, Object> q = Json.asObject(o);
            if (q != null) {
                EQuest e = new EQuest();
                e.pack = String.valueOf(q.get("pack"));
                e.file = String.valueOf(q.get("file"));
                e.json = String.valueOf(q.get("json"));
                parseInto(e);
                quests.add(e);
            }
        }
        for (Object o : list(m, "drafts")) {
            Map<String, Object> q = Json.asObject(o);
            if (q != null) {
                EQuest e = new EQuest();
                e.draft = true;
                e.file = String.valueOf(q.get("name"));
                e.json = String.valueOf(q.get("json"));
                parseInto(e);
                quests.add(e);
            }
        }
        for (Object o : list(m, "issues")) {
            Map<String, Object> i = Json.asObject(o);
            if (i != null) {
                Severity sev = "ERROR".equals(i.get("sev")) ? Severity.ERROR : "WARNING".equals(i.get("sev")) ? Severity.WARNING : Severity.INFO;
                issues.add(new Issue(sev, String.valueOf(i.get("code")), String.valueOf(i.get("quest")), String.valueOf(i.get("file")),
                        String.valueOf(i.get("path")), String.valueOf(i.get("msg")), Boolean.TRUE.equals(i.get("blocking"))));
            }
        }
        for (Object o : list(m, "dimensions")) {
            dimensions.add(String.valueOf(o));
        }
        for (Object o : list(m, "approved")) {
            approved.add(String.valueOf(o));
        }
        commandsEnabled = Boolean.TRUE.equals(m.get("commandsEnabled"));
        loaded = true;
        stale = false;
    }

    private static List<Object> list(Map<String, Object> m, String key) {
        List<Object> l = Json.asArray(m.get(key));
        return l == null ? new ArrayList<>() : l;
    }

    private static void parseInto(EQuest e) {
        QuestCodec.ParseResult pr = QuestCodec.parse(e.json, e.file);
        e.def = pr.quest();
        for (Issue i : pr.issues()) {
            if (i.severity() == Severity.ERROR) {
                e.problems.add(i.path() + ": " + i.message());
            }
        }
    }

    public static List<Issue> issuesFor(String questId) {
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.questId().equals(questId)) {
                out.add(i);
            }
        }
        return out;
    }

    /** Draws the latest server reply (publish result, validation summary...) for 10 seconds, wrapped to two lines. */
    public static void drawStatus(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.Font font,
                                  com.questjournal.client.ui.Theme t, int x, int y, int w) {
        if (resultText.isEmpty() || Util.getMillis() - resultAt > 10_000) {
            return;
        }
        int shown = 0;
        for (net.minecraft.util.FormattedCharSequence l : font.split(net.minecraft.network.chat.FormattedText.of(resultText), w)) {
            if (shown++ >= 2) {
                break;
            }
            g.drawString(font, l, x, y, resultOk ? t.ok : t.bad, false);
            y += 10;
        }
    }
}
