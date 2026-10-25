package com.questjournal.core.state;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything saved for one player, keyed by stable quest ids. Entries for quests that no longer exist are kept
 * (never deleted) so progress comes back if the quest is restored or a mod is re-installed.
 */
public final class PlayerProgress {
    public final UUID uuid;
    public final Map<String, QuestProgress> quests = new HashMap<>();
    /** Unknown top-level fields of the save file, preserved across load/save. */
    public Map<String, Object> extra = new LinkedHashMap<>();
    public boolean dirty;

    public PlayerProgress(UUID uuid) {
        this.uuid = uuid;
    }

    public QuestProgress get(String questId) {
        return quests.get(questId);
    }
}
