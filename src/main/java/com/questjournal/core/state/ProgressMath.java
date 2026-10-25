package com.questjournal.core.state;

import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;

/** Small helpers shared by the server, the client UI and tests. */
public final class ProgressMath {
    private ProgressMath() {
    }

    /** 0..1 completion of the required objectives; claimable/completed quests are always 1. */
    public static float fraction(QuestDefinition q, QuestStatus status, int[] counts) {
        if (status == QuestStatus.COMPLETED || status == QuestStatus.CLAIMABLE) {
            return 1f;
        }
        float sum = 0;
        int n = 0;
        for (int i = 0; i < q.objectives.size(); i++) {
            ObjectiveDefinition o = q.objectives.get(i);
            if (!o.required) {
                continue;
            }
            n++;
            int c = counts != null && i < counts.length ? counts[i] : 0;
            sum += Math.min(1f, c / (float) o.requiredCount());
        }
        return n == 0 ? 0f : sum / n;
    }
}
