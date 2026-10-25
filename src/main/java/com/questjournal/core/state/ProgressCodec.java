package com.questjournal.core.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.core.model.QuestStatus;

/** Reads and writes a player's saved quest progress (compact JSON, versioned). */
public final class ProgressCodec {
    public static final int VERSION = 1;

    private ProgressCodec() {
    }

    public static String toJson(PlayerProgress p) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", (long) VERSION);
        root.put("uuid", p.uuid.toString());
        Map<String, Object> quests = new LinkedHashMap<>();
        for (Map.Entry<String, QuestProgress> e : p.quests.entrySet()) {
            QuestProgress qp = e.getValue();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("status", qp.status.name().toLowerCase());
            q.put("since", qp.since);
            if (!qp.objectives.isEmpty()) {
                Map<String, Object> objs = new LinkedHashMap<>();
                for (Map.Entry<String, ObjectiveProgress> oe : qp.objectives.entrySet()) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("n", (long) oe.getValue().count);
                    o.put("d", oe.getValue().done);
                    o.put("r", oe.getValue().revealed);
                    objs.put(oe.getKey(), o);
                }
                q.put("objectives", objs);
            }
            if (!qp.grantedRewards.isEmpty()) {
                q.put("rewards", new ArrayList<Object>(qp.grantedRewards));
            }
            if (!qp.dialoguesDone.isEmpty()) {
                q.put("dialogues", new ArrayList<Object>(qp.dialoguesDone));
            }
            quests.put(e.getKey(), q);
        }
        root.put("quests", quests);
        for (Map.Entry<String, Object> e : p.extra.entrySet()) {
            root.putIfAbsent(e.getKey(), e.getValue());
        }
        return Json.write(root, false);
    }

    /** @throws JsonException if the text is not valid JSON or has an unusable structure (caller quarantines the file) */
    public static PlayerProgress fromJson(String text, UUID uuid) throws JsonException {
        Object parsed = Json.parse(text);
        Map<String, Object> root = Json.asObject(parsed);
        if (root == null) {
            throw new JsonException("Progress file must contain a JSON object", 1, 1);
        }
        PlayerProgress p = new PlayerProgress(uuid);
        Map<String, Object> quests = root.get("quests") == null ? new LinkedHashMap<>() : Json.asObject(root.get("quests"));
        if (quests == null) {
            throw new JsonException("'quests' must be an object", 1, 1);
        }
        for (Map.Entry<String, Object> e : quests.entrySet()) {
            Map<String, Object> q = Json.asObject(e.getValue());
            if (q == null) {
                throw new JsonException("Quest entry '" + e.getKey() + "' must be an object", 1, 1);
            }
            QuestProgress qp = new QuestProgress();
            QuestStatus st = q.get("status") instanceof String ? QuestStatus.byName((String) q.get("status")) : null;
            if (st == null || !st.persisted()) {
                throw new JsonException("Quest entry '" + e.getKey() + "' has an invalid status", 1, 1);
            }
            qp.status = st;
            qp.since = q.get("since") instanceof Number ? ((Number) q.get("since")).longValue() : 0L;
            Map<String, Object> objs = Json.asObject(q.get("objectives"));
            if (objs != null) {
                for (Map.Entry<String, Object> oe : objs.entrySet()) {
                    Map<String, Object> o = Json.asObject(oe.getValue());
                    if (o == null) {
                        continue;
                    }
                    ObjectiveProgress op = new ObjectiveProgress();
                    op.count = o.get("n") instanceof Number ? Math.max(0, ((Number) o.get("n")).intValue()) : 0;
                    op.done = Boolean.TRUE.equals(o.get("d"));
                    op.revealed = Boolean.TRUE.equals(o.get("r"));
                    qp.objectives.put(oe.getKey(), op);
                }
            }
            for (String key : new String[]{"rewards", "dialogues"}) {
                List<Object> l = Json.asArray(q.get(key));
                if (l != null) {
                    for (Object o : l) {
                        if (o instanceof String) {
                            (key.equals("rewards") ? qp.grantedRewards : qp.dialoguesDone).add((String) o);
                        }
                    }
                }
            }
            p.quests.put(e.getKey(), qp);
        }
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!e.getKey().equals("version") && !e.getKey().equals("uuid") && !e.getKey().equals("quests")) {
                p.extra.put(e.getKey(), e.getValue());
            }
        }
        return p;
    }
}
