package com.questjournal.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single quest data model shared by JSON quest packs, the in-game editor and the network sync.
 * Plain mutable fields keep the editor simple; the server treats loaded definitions as read-only.
 */
public final class QuestDefinition {
    public String id = "";
    public String title = "";
    public String shortDescription = "";
    public String description = "";
    public QuestCategory category = QuestCategory.MAIN_STORY;
    /** Optional item id used as the quest icon. */
    public String icon = "";
    public boolean enabled = true;
    /** Lower numbers are listed first inside a category. */
    public int sortOrder = 0;
    public Visibility visibility = Visibility.ALWAYS;
    public AcceptanceMode acceptance = AcceptanceMode.MANUAL;
    public List<String> prerequisites = new ArrayList<>();
    public PrerequisiteMode prerequisiteMode = PrerequisiteMode.ALL;
    public ObjectiveMode objectiveMode = ObjectiveMode.ALL_AT_ONCE;
    public AbandonMode abandon = AbandonMode.PRESERVE_PROGRESS;
    public List<ObjectiveDefinition> objectives = new ArrayList<>();
    public List<RewardDefinition> rewards = new ArrayList<>();
    public List<LorePage> lore = new ArrayList<>();
    public List<DialogueSequence> dialogues = new ArrayList<>();
    public Map<String, Object> extra = new LinkedHashMap<>();

    /** Cache of the compact client JSON (filled lazily by the server when syncing; see QuestCodec.clientJson). */
    public volatile String clientJsonCache;

    public QuestDefinition copy() {
        QuestDefinition q = new QuestDefinition();
        q.id = id;
        q.title = title;
        q.shortDescription = shortDescription;
        q.description = description;
        q.category = category;
        q.icon = icon;
        q.enabled = enabled;
        q.sortOrder = sortOrder;
        q.visibility = visibility;
        q.acceptance = acceptance;
        q.prerequisites = new ArrayList<>(prerequisites);
        q.prerequisiteMode = prerequisiteMode;
        q.objectiveMode = objectiveMode;
        q.abandon = abandon;
        for (ObjectiveDefinition o : objectives) {
            q.objectives.add(o.copy());
        }
        for (RewardDefinition r : rewards) {
            q.rewards.add(r.copy());
        }
        for (LorePage p : lore) {
            q.lore.add(p.copy());
        }
        for (DialogueSequence d : dialogues) {
            q.dialogues.add(d.copy());
        }
        q.extra = new LinkedHashMap<>(extra);
        return q;
    }

    /** Replaces every field with a deep copy of {@code o} (used when the editor applies edited JSON to the open draft). */
    public void assignFrom(QuestDefinition o) {
        QuestDefinition c = o.copy();
        id = c.id;
        title = c.title;
        shortDescription = c.shortDescription;
        description = c.description;
        category = c.category;
        icon = c.icon;
        enabled = c.enabled;
        sortOrder = c.sortOrder;
        visibility = c.visibility;
        acceptance = c.acceptance;
        prerequisites = c.prerequisites;
        prerequisiteMode = c.prerequisiteMode;
        objectiveMode = c.objectiveMode;
        abandon = c.abandon;
        objectives = c.objectives;
        rewards = c.rewards;
        lore = c.lore;
        dialogues = c.dialogues;
        extra = c.extra;
        clientJsonCache = null;
    }

    public int indexOfObjective(String objectiveId) {
        for (int i = 0; i < objectives.size(); i++) {
            if (objectives.get(i).id.equals(objectiveId)) {
                return i;
            }
        }
        return -1;
    }

    public DialogueSequence dialogue(String dialogueId) {
        for (DialogueSequence d : dialogues) {
            if (d.id.equals(dialogueId)) {
                return d;
            }
        }
        return null;
    }

    public boolean hasCommandRewards() {
        for (RewardDefinition r : rewards) {
            if (r.type == RewardType.COMMAND) {
                return true;
            }
        }
        return false;
    }
}
