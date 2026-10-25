package com.questjournal.core.state;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.questjournal.core.model.QuestStatus;

/** One player's saved state for one quest. Only persisted statuses are ever stored here. */
public final class QuestProgress {
    public QuestStatus status = QuestStatus.AVAILABLE;
    /** Epoch millis of the last status change (used for "newest/oldest" sorting). */
    public long since;
    public final Map<String, ObjectiveProgress> objectives = new LinkedHashMap<>();
    /** Reward ids that were already delivered. Makes a repeated or partially failed claim idempotent. */
    public final Set<String> grantedRewards = new LinkedHashSet<>();
    public final Set<String> dialoguesDone = new LinkedHashSet<>();

    public ObjectiveProgress objective(String id) {
        return objectives.computeIfAbsent(id, k -> new ObjectiveProgress());
    }

    public void resetProgress() {
        objectives.clear();
        grantedRewards.clear();
    }
}
