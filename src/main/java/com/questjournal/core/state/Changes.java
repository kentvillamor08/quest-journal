package com.questjournal.core.state;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Collects what an engine call changed so the server layer can sync, notify and run follow-up checks once. */
public final class Changes {
    /** Quests whose state must be re-sent to the client. */
    public final Set<String> dirtyQuests = new LinkedHashSet<>();
    public final List<Notice> notices = new ArrayList<>();
    /** Quests that just became active or revealed objectives (run inventory / dimension checks for them). */
    public final Set<String> activated = new LinkedHashSet<>();
    /** The set of active objectives changed: rebuild the player's {@link ActiveIndex}. */
    public boolean indexDirty;

    public void touch(String questId) {
        dirtyQuests.add(questId);
    }

    public void notice(NoticeType type, String questId, String objectiveId, int count, int target) {
        notices.add(new Notice(type, questId, objectiveId, count, target));
    }

    public boolean isEmpty() {
        return dirtyQuests.isEmpty() && notices.isEmpty() && activated.isEmpty() && !indexDirty;
    }
}
