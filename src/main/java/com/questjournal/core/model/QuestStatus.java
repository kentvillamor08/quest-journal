package com.questjournal.core.model;

/**
 * What a player sees for a quest. AVAILABLE, ACTIVE, CLAIMABLE, COMPLETED and ABANDONED are persisted;
 * LOCKED (prerequisites missing) and INVALID (broken definition / missing mod content) are derived.
 */
public enum QuestStatus {
    LOCKED("Locked"),
    AVAILABLE("Available"),
    ACTIVE("Active"),
    CLAIMABLE("Ready to claim"),
    COMPLETED("Completed"),
    ABANDONED("Abandoned"),
    INVALID("Needs attention");

    public final String label;

    QuestStatus(String label) {
        this.label = label;
    }

    public boolean persisted() {
        return this != LOCKED && this != INVALID;
    }

    public static QuestStatus byName(String s) {
        for (QuestStatus q : values()) {
            if (q.name().equalsIgnoreCase(s)) {
                return q;
            }
        }
        return null;
    }
}
