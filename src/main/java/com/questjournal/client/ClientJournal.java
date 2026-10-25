package com.questjournal.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.questjournal.QuestJournalMod;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.model.ObjectiveMode;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.state.ProgressMath;

import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;

/** Client-side copy of the quests the server chose to show this player, plus their state. Rendering reads from here only. */
public final class ClientJournal {
    public static final ClientJournal I = new ClientJournal();

    public static final class CQuest {
        public QuestDefinition def;
        public QuestStatus status = QuestStatus.LOCKED;
        public long since;
        public int[] counts = new int[0];
        public boolean[] done = new boolean[0];
        public boolean[] revealed = new boolean[0];
        public List<String> problems = new ArrayList<>();
        public Set<String> dialoguesDone = new HashSet<>();
        /** Lazily created icon stacks (rendering caches). */
        public ItemStack iconStack;
        public List<ItemStack> rewardStacks;
        private String haystack;

        public String haystack() {
            if (haystack == null) {
                haystack = (def.title + "\n" + def.shortDescription + "\n" + def.description + "\n" + def.id).toLowerCase(Locale.ROOT);
            }
            return haystack;
        }

        public float progress() {
            return ProgressMath.fraction(def, status, counts);
        }

        public boolean objectiveVisible(int i) {
            return def.objectiveMode == ObjectiveMode.ALL_AT_ONCE || (i < revealed.length && revealed[i]);
        }

        public int doneCount() {
            int n = 0;
            for (boolean b : done) {
                if (b) {
                    n++;
                }
            }
            return n;
        }

        /** Sample state for the editor's "preview as a player" (never sent anywhere). */
        public static CQuest preview(QuestDefinition def, QuestStatus status) {
            CQuest c = new CQuest();
            c.def = def;
            c.status = status;
            int n = def.objectives.size();
            c.counts = new int[n];
            c.done = new boolean[n];
            c.revealed = new boolean[n];
            for (int i = 0; i < n; i++) {
                int need = def.objectives.get(i).requiredCount();
                boolean finished = status == QuestStatus.CLAIMABLE || status == QuestStatus.COMPLETED;
                c.counts[i] = finished ? need : (status == QuestStatus.ACTIVE && i == 0 ? Math.max(0, need / 2) : 0);
                c.done[i] = finished;
                c.revealed[i] = status != QuestStatus.LOCKED && status != QuestStatus.AVAILABLE && (i == 0 || finished || def.objectiveMode == ObjectiveMode.ALL_AT_ONCE);
            }
            if (status == QuestStatus.INVALID) {
                c.problems.add("Example: an item used by this quest is not registered (mod not installed).");
            }
            return c;
        }
    }

    public final Map<String, CQuest> quests = new LinkedHashMap<>();
    public int version;
    public boolean synced;
    public boolean admin;
    public String resultMsg = "";
    public boolean resultOk;
    public long resultAt;
    private Map<String, CQuest> staging;

    public void clear() {
        quests.clear();
        staging = null;
        synced = false;
        admin = false;
        version++;
        com.questjournal.client.ui.Names.clear();
    }

    public void setResult(boolean ok, String msg) {
        resultOk = ok;
        resultMsg = msg;
        resultAt = Util.getMillis();
    }

    /** Full sync arrives in batches; the visible data is swapped only when the last batch has arrived. */
    @SuppressWarnings("unchecked")
    public void onSync(Map<String, Object> m) {
        if (Boolean.TRUE.equals(m.get("first")) || staging == null) {
            staging = new LinkedHashMap<>();
        }
        admin = Boolean.TRUE.equals(m.get("admin"));
        List<Object> list = Json.asArray(m.get("quests"));
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> e = Json.asObject(o);
                if (e != null) {
                    apply(e, staging);
                }
            }
        }
        if (Boolean.TRUE.equals(m.get("last"))) {
            quests.clear();
            quests.putAll(staging);
            staging = null;
            synced = true;
            version++;
        }
    }

    public void onDelta(Map<String, Object> m) {
        List<Object> removed = Json.asArray(m.get("removed"));
        if (removed != null) {
            for (Object o : removed) {
                quests.remove(String.valueOf(o));
            }
        }
        List<Object> updates = Json.asArray(m.get("updates"));
        if (updates != null) {
            for (Object o : updates) {
                Map<String, Object> e = Json.asObject(o);
                if (e != null) {
                    apply(e, quests);
                }
            }
        }
        version++;
    }

    private void apply(Map<String, Object> e, Map<String, CQuest> into) {
        String id = String.valueOf(e.get("id"));
        CQuest q = into.get(id);
        Map<String, Object> defMap = Json.asObject(e.get("def"));
        if (defMap != null) {
            QuestCodec.ParseResult pr = QuestCodec.parseValue(defMap, "sync");
            if (!pr.ok()) {
                QuestJournalMod.LOGGER.warn("[QuestJournal] Ignoring a quest definition from the server: {}", id);
                return;
            }
            if (q == null) {
                q = new CQuest();
                into.put(id, q);
            }
            q.def = pr.quest();
            q.iconStack = null;
            q.rewardStacks = null;
            q.haystack = null;
        }
        if (q == null || q.def == null) {
            return; // state for a quest whose definition we never received
        }
        Map<String, Object> st = Json.asObject(e.get("st"));
        if (st == null) {
            return;
        }
        QuestStatus s = QuestStatus.byName(String.valueOf(st.get("s")));
        q.status = s == null ? QuestStatus.INVALID : s;
        q.since = st.get("t") instanceof Number ? ((Number) st.get("t")).longValue() : 0L;
        int n = q.def.objectives.size();
        q.counts = new int[n];
        q.done = new boolean[n];
        q.revealed = new boolean[n];
        List<Object> objs = Json.asArray(st.get("o"));
        for (int i = 0; objs != null && i < n && i < objs.size(); i++) {
            List<Object> t = Json.asArray(objs.get(i));
            if (t != null && t.size() >= 3) {
                q.counts[i] = ((Number) t.get(0)).intValue();
                q.done[i] = ((Number) t.get(1)).intValue() != 0;
                q.revealed[i] = ((Number) t.get(2)).intValue() != 0;
            }
        }
        q.dialoguesDone = new HashSet<>();
        List<Object> dl = Json.asArray(st.get("dl"));
        if (dl != null) {
            for (Object o : dl) {
                q.dialoguesDone.add(String.valueOf(o));
            }
        }
        q.problems = new ArrayList<>();
        List<Object> inv = Json.asArray(st.get("inv"));
        if (inv != null) {
            for (Object o : inv) {
                q.problems.add(String.valueOf(o));
            }
        }
    }
}
