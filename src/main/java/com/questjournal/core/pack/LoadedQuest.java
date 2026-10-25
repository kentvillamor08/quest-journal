package com.questjournal.core.pack;

import com.questjournal.core.model.QuestDefinition;

/**
 * A quest definition together with where it came from. {@code stale} means the file on disk currently fails to
 * load and this is the last known good version that is being kept alive.
 */
public record LoadedQuest(QuestDefinition def, String packId, String relPath, boolean stale) {
    /** Unique key of the source file: "packId/quests/some/file.json". */
    public String fileKey() {
        return packId + "/" + relPath;
    }
}
