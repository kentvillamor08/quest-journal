package com.questjournal.core.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.questjournal.core.validate.Issue;

/** Everything one pass over the packs directory produced. Nothing here touches the live registry. */
public final class LoadResult {
    public final List<PackInfo> packs = new ArrayList<>();
    public final List<LoadedQuest> quests = new ArrayList<>();
    public final List<Issue> issues = new ArrayList<>();
    /** Source files that could not be loaded (the files themselves are never modified or deleted). */
    public int failedFiles;

    public Map<String, LoadedQuest> fileMap() {
        Map<String, LoadedQuest> m = new LinkedHashMap<>();
        for (LoadedQuest q : quests) {
            m.put(q.fileKey(), q);
        }
        return m;
    }

    public Map<String, PackInfo> packMap() {
        Map<String, PackInfo> m = new LinkedHashMap<>();
        for (PackInfo p : packs) {
            m.put(p.id(), p);
        }
        return m;
    }

    public Map<String, String> packOfQuest() {
        Map<String, String> m = new LinkedHashMap<>();
        for (LoadedQuest q : quests) {
            m.put(q.def().id, q.packId());
        }
        return m;
    }
}
