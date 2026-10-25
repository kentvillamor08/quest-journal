package com.questjournal.core.validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.security.ApprovalChecker;
import com.questjournal.core.security.CommandPolicy;
import com.questjournal.core.util.Ids;

/**
 * Cross-quest validation: prerequisite references, dependency cycles, registry references (missing mods) and
 * command-reward policy. It never modifies or drops a definition; it only reports. Quests with BLOCKING issues
 * are shown to players as "needs attention" and cannot progress or be claimed until the problem is fixed.
 */
public final class QuestValidator {
    private QuestValidator() {
    }

    /**
     * @param reg       registry lookups; null skips the registry checks
     * @param policy    command policy; null skips command checks
     * @param approvals command approvals; null skips the approval warning
     */
    public static ValidationReport validate(Collection<QuestDefinition> quests, RegistryChecker reg,
                                            CommandPolicy policy, ApprovalChecker approvals) {
        ValidationReport rep = new ValidationReport();
        Map<String, QuestDefinition> byId = new LinkedHashMap<>();
        for (QuestDefinition q : quests) {
            byId.putIfAbsent(q.id, q);
        }

        for (QuestDefinition q : byId.values()) {
            for (int i = 0; i < q.prerequisites.size(); i++) {
                String pre = q.prerequisites.get(i);
                if (!byId.containsKey(pre)) {
                    rep.add(Issue.error("unknown_prerequisite", q.id, "prerequisites[" + i + "]",
                            "Prerequisite quest '" + pre + "' does not exist, so this quest can never unlock.", true));
                }
            }
            if (reg != null) {
                checkReferences(q, reg, rep);
            }
            checkCommands(q, policy, approvals, rep);
        }

        // Dependency cycles: every quest inside a cycle can never unlock, so each one is reported and blocked.
        Set<String> inCycle = new HashSet<>();
        for (List<String> cycle : findCycles(byId)) {
            inCycle.addAll(cycle);
            String path = describeCycle(cycle);
            for (String id : cycle) {
                rep.add(Issue.error("prerequisite_cycle", id, "prerequisites",
                        "Circular prerequisite chain: " + path + ". None of these quests can ever unlock until the loop is broken.", true));
            }
        }

        // Quests whose prerequisites are themselves blocked will wait forever; say so (advisory).
        Set<String> blocked = new HashSet<>();
        for (Issue i : rep.issues()) {
            if (i.blocking()) {
                blocked.add(i.questId());
            }
        }
        for (QuestDefinition q : byId.values()) {
            for (int i = 0; i < q.prerequisites.size(); i++) {
                String pre = q.prerequisites.get(i);
                if (blocked.contains(pre) && !inCycle.contains(q.id)) {
                    rep.add(Issue.warn("prerequisite_blocked", q.id, "prerequisites[" + i + "]",
                            "Prerequisite '" + pre + "' currently needs attention, so this quest will stay locked."));
                }
            }
        }
        return rep;
    }

    private static void checkReferences(QuestDefinition q, RegistryChecker reg, ValidationReport rep) {
        for (int i = 0; i < q.objectives.size(); i++) {
            ObjectiveDefinition o = q.objectives.get(i);
            if (!o.target.isEmpty() && !reg.exists(o.type.targetKind, o.target)) {
                boolean blocking = o.required;
                rep.add(new Issue(blocking ? Severity.ERROR : Severity.WARNING, "missing_registry", q.id, "",
                        "objectives[" + i + "].target",
                        missingMessage(reg, o.type.targetKind, o.target, "objective '" + o.id + "'")
                                + (blocking ? "" : " (optional objective: the quest itself stays usable)"),
                        blocking));
            }
        }
        for (int i = 0; i < q.rewards.size(); i++) {
            RewardDefinition r = q.rewards.get(i);
            if (r.type == RewardType.ITEM) {
                if (!r.item.isEmpty() && !reg.exists(RegistryKind.ITEM, r.item)) {
                    rep.add(Issue.error("missing_registry", q.id, "rewards[" + i + "].item",
                            missingMessage(reg, RegistryKind.ITEM, r.item, "reward '" + r.id + "'")
                                    + " The reward is never replaced by another item.", true));
                } else if (!r.nbt.isEmpty()) {
                    String problem = reg.validateNbt(r.nbt);
                    if (problem != null) {
                        rep.add(Issue.error("bad_nbt", q.id, "rewards[" + i + "].nbt",
                                "The item NBT of reward '" + r.id + "' is not valid SNBT: " + problem, true));
                    }
                }
            } else if (r.type == RewardType.CUSTOM && !r.provider.isEmpty() && !reg.rewardProviderExists(r.provider)) {
                rep.add(Issue.error("missing_reward_provider", q.id, "rewards[" + i + "].provider",
                        "Reward provider '" + r.provider + "' is not registered (is the integrating mod installed?). "
                                + "The reward is preserved and will work again when the provider is available.", true));
            }
        }
        if (!q.icon.isEmpty() && !reg.exists(RegistryKind.ITEM, q.icon)) {
            rep.add(Issue.warn("missing_registry", q.id, "icon",
                    missingMessage(reg, RegistryKind.ITEM, q.icon, "the quest icon") + " A default icon is shown instead."));
        }
    }

    private static void checkCommands(QuestDefinition q, CommandPolicy policy, ApprovalChecker approvals, ValidationReport rep) {
        for (int i = 0; i < q.rewards.size(); i++) {
            RewardDefinition r = q.rewards.get(i);
            if (r.type != RewardType.COMMAND) {
                continue;
            }
            if (policy != null) {
                List<String> reasons = policy.check(r.command);
                if (!reasons.isEmpty()) {
                    rep.add(Issue.warn("command_not_allowed", q.id, "rewards[" + i + "].command",
                            "Command reward '" + r.id + "' will not run: " + String.join("; ", reasons) + "."));
                }
            }
            if (approvals != null && !approvals.isApproved(CommandPolicy.fingerprint(q.id, r.id, r.command))) {
                rep.add(Issue.warn("command_unapproved", q.id, "rewards[" + i + "].command",
                        "Command reward '" + r.id + "' has not been approved by an operator and will not run. "
                                + "Review it, then run /questjournal approve " + q.id));
            }
        }
    }

    static String missingMessage(RegistryChecker reg, RegistryKind kind, String id, String what) {
        String ns = Ids.namespaceOf(id);
        String why = reg.namespaceLoaded(ns)
                ? "the mod '" + ns + "' is loaded but has no such entry"
                : "the mod '" + ns + "' is not installed";
        return kind.label + " '" + id + "' used by " + what + " is not registered (" + why + ").";
    }

    private static String describeCycle(List<String> cycle) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(cycle.size(), 8);
        for (int i = 0; i < shown; i++) {
            sb.append(cycle.get(i)).append(" -> ");
        }
        sb.append(cycle.size() > shown ? "... (" + cycle.size() + " quests)" : cycle.get(0));
        return sb.toString();
    }

    // ------------------------------------------------------------------ iterative Tarjan SCC (no recursion: chains can be very long)

    private static final class Frame {
        final String node;
        final Iterator<String> it;

        Frame(String node, Iterator<String> it) {
            this.node = node;
            this.it = it;
        }
    }

    /** Returns every strongly connected component that is a real cycle (size > 1 or a self loop). */
    static List<List<String>> findCycles(Map<String, QuestDefinition> byId) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> result = new ArrayList<>();
        int counter = 0;

        for (String start : byId.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Frame> work = new ArrayDeque<>();
            index.put(start, counter);
            low.put(start, counter);
            counter++;
            stack.push(start);
            onStack.add(start);
            work.push(new Frame(start, preds(byId, start).iterator()));

            while (!work.isEmpty()) {
                Frame f = work.peek();
                String v = f.node;
                if (f.it.hasNext()) {
                    String w = f.it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter);
                        low.put(w, counter);
                        counter++;
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Frame(w, preds(byId, w).iterator()));
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    work.pop();
                    if (low.get(v).equals(index.get(v))) {
                        List<String> comp = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            comp.add(w);
                        } while (!w.equals(v));
                        if (comp.size() > 1 || preds(byId, v).contains(v)) {
                            java.util.Collections.reverse(comp);
                            result.add(comp);
                        }
                    }
                    if (!work.isEmpty()) {
                        String parent = work.peek().node;
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                }
            }
        }
        return result;
    }

    private static List<String> preds(Map<String, QuestDefinition> byId, String id) {
        List<String> out = new ArrayList<>();
        for (String p : byId.get(id).prerequisites) {
            if (byId.containsKey(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
