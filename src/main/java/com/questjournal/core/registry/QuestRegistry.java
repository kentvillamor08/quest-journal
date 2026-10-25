package com.questjournal.core.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.ValidationReport;

/**
 * Immutable snapshot of all live quest definitions plus the indexes the server needs. A reload builds a complete
 * new snapshot and swaps one reference, so readers never see a half-applied pack.
 */
public final class QuestRegistry {
    private static final Pattern OBJECTIVE_PATH = Pattern.compile("^objectives\\[(\\d+)]\\.target$");

    public static final QuestRegistry EMPTY = new QuestRegistry(0, new ArrayList<>(), new HashMap<>(), new ValidationReport());

    public final int generation;
    public final ValidationReport report;
    private final Map<String, QuestDefinition> byId = new LinkedHashMap<>();
    private final List<QuestDefinition> ordered;
    private final Map<String, List<Issue>> blockers = new HashMap<>();
    private final Set<String> unresolvedObjectives = new HashSet<>();
    private final Map<String, List<String>> dependents = new HashMap<>();
    private final Map<String, String> packOf;

    private QuestRegistry(int generation, List<QuestDefinition> quests, Map<String, String> packOf, ValidationReport report) {
        this.generation = generation;
        this.report = report;
        this.packOf = packOf;
        List<QuestDefinition> sorted = new ArrayList<>(quests);
        sorted.sort(Comparator.<QuestDefinition>comparingInt(q -> q.category.ordinal())
                .thenComparingInt(q -> q.sortOrder)
                .thenComparing(q -> q.title.toLowerCase())
                .thenComparing(q -> q.id));
        this.ordered = Collections.unmodifiableList(sorted);
        for (QuestDefinition q : sorted) {
            byId.put(q.id, q);
        }
        for (QuestDefinition q : sorted) {
            for (String pre : q.prerequisites) {
                dependents.computeIfAbsent(pre, k -> new ArrayList<>()).add(q.id);
            }
        }
        for (Issue i : report.issues()) {
            if (!byId.containsKey(i.questId())) {
                continue;
            }
            if (i.blocking()) {
                blockers.computeIfAbsent(i.questId(), k -> new ArrayList<>()).add(i);
            }
            if (i.code().equals("missing_registry")) {
                Matcher m = OBJECTIVE_PATH.matcher(i.path());
                QuestDefinition q = byId.get(i.questId());
                if (m.matches()) {
                    int idx = Integer.parseInt(m.group(1));
                    if (idx >= 0 && idx < q.objectives.size()) {
                        unresolvedObjectives.add(q.id + "#" + q.objectives.get(idx).id);
                    }
                }
            }
        }
    }

    public static QuestRegistry build(int generation, Collection<QuestDefinition> quests, Map<String, String> packOf,
                                      ValidationReport report) {
        return new QuestRegistry(generation, new ArrayList<>(quests), new HashMap<>(packOf), report);
    }

    public QuestDefinition get(String id) {
        return byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** All quests in display order (category, sort order, title). */
    public List<QuestDefinition> all() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    /** True if the quest has blocking problems (missing mod content, broken prerequisites, cycles). */
    public boolean isInvalid(String questId) {
        return blockers.containsKey(questId);
    }

    public List<Issue> blockers(String questId) {
        List<Issue> l = blockers.get(questId);
        return l == null ? Collections.emptyList() : l;
    }

    /** True if this specific objective references content that is not registered (it can never progress). */
    public boolean isObjectiveUnresolved(String questId, String objectiveId) {
        return unresolvedObjectives.contains(questId + "#" + objectiveId);
    }

    public List<String> dependents(String questId) {
        List<String> l = dependents.get(questId);
        return l == null ? Collections.emptyList() : l;
    }

    public String packOf(String questId) {
        return packOf.getOrDefault(questId, "");
    }
}
