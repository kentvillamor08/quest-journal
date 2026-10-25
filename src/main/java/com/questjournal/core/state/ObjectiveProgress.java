package com.questjournal.core.state;

/** Saved progress of one objective, addressed by its stable objective id. */
public final class ObjectiveProgress {
    public int count;
    public boolean done;
    /** Sequential quests: has this objective been unlocked yet? */
    public boolean revealed;
}
