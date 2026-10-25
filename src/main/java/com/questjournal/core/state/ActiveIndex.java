package com.questjournal.core.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.questjournal.core.model.ObjectiveType;

/**
 * Per-player index of ACTIVE objectives that can currently progress, keyed by objective type and then by target
 * registry id. A game event does one map lookup instead of scanning quests; if the player has no objective of that
 * type the event is discarded after a single EnumMap read.
 */
public final class ActiveIndex {
    public record Ref(String questId, int objectiveIndex) {
    }

    private final EnumMap<ObjectiveType, Map<String, List<Ref>>> map = new EnumMap<>(ObjectiveType.class);

    void add(ObjectiveType type, String target, Ref ref) {
        map.computeIfAbsent(type, t -> new HashMap<>()).computeIfAbsent(target, t -> new ArrayList<>(2)).add(ref);
    }

    public boolean has(ObjectiveType type) {
        Map<String, List<Ref>> m = map.get(type);
        return m != null && !m.isEmpty();
    }

    public List<Ref> get(ObjectiveType type, String target) {
        Map<String, List<Ref>> m = map.get(type);
        if (m == null) {
            return Collections.emptyList();
        }
        List<Ref> l = m.get(target);
        return l == null ? Collections.emptyList() : l;
    }

    /** All refs of one type (used by the restrained periodic checks for location / inventory objectives). */
    public Map<String, List<Ref>> byType(ObjectiveType type) {
        Map<String, List<Ref>> m = map.get(type);
        return m == null ? Collections.emptyMap() : m;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }
}
