package com.questjournal.core.state;

/** A meaningful event worth a subtle on-screen notification. */
public record Notice(NoticeType type, String questId, String objectiveId, int count, int target) {
}
