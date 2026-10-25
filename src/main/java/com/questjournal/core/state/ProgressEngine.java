package com.questjournal.core.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.questjournal.core.model.AbandonMode;
import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.DialogueGate;
import com.questjournal.core.model.DialogueSequence;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveMode;
import com.questjournal.core.model.PrerequisiteMode;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.registry.QuestRegistry;

/**
 * The quest state machine. It contains no Minecraft code: the Forge layer translates game events into calls on
 * this class and applies the returned {@link Changes} (sync, notifications, follow-up checks). Every rule the
 * prompt cares about lives here so it can be unit-tested: availability, manual/auto start, prerequisites,
 * sequential reveal, abandon modes, claim idempotency and stable-id progress migration.
 */
public final class ProgressEngine {
    private final QuestRegistry reg;

    public ProgressEngine(QuestRegistry reg) {
        this.reg = reg;
    }

    public QuestRegistry registry() {
        return reg;
    }

    // ================================================================== status

    public boolean prerequisitesMet(PlayerProgress p, QuestDefinition q) {
        if (q.prerequisites.isEmpty()) {
            return true;
        }
        for (String pre : q.prerequisites) {
            QuestProgress qp = p.quests.get(pre);
            boolean done = qp != null && qp.status == QuestStatus.COMPLETED;
            if (q.prerequisiteMode == PrerequisiteMode.ANY) {
                if (done) {
                    return true;
                }
            } else if (!done) {
                return false;
            }
        }
        return q.prerequisiteMode != PrerequisiteMode.ANY;
    }

    /** Status ignoring the INVALID override (used for visibility so hidden quests stay hidden). */
    public QuestStatus baseStatus(PlayerProgress p, QuestDefinition q) {
        QuestProgress qp = p.quests.get(q.id);
        if (qp != null) {
            return qp.status;
        }
        return prerequisitesMet(p, q) ? QuestStatus.AVAILABLE : QuestStatus.LOCKED;
    }

    public QuestStatus statusOf(PlayerProgress p, QuestDefinition q) {
        return reg.isInvalid(q.id) ? QuestStatus.INVALID : baseStatus(p, q);
    }

    public boolean isVisible(PlayerProgress p, QuestDefinition q) {
        if (!q.enabled) {
            return false;
        }
        QuestStatus s = baseStatus(p, q);
        switch (q.visibility) {
            case WHEN_AVAILABLE:
                return s != QuestStatus.LOCKED;
            case WHEN_ACTIVE:
                return s == QuestStatus.ACTIVE || s == QuestStatus.CLAIMABLE
                        || s == QuestStatus.COMPLETED || s == QuestStatus.ABANDONED;
            default:
                return true;
        }
    }

    // ================================================================== availability / start

    public void evaluateAll(PlayerProgress p, long now, Changes ch) {
        for (QuestDefinition q : reg.all()) {
            evaluateOne(p, q, now, ch);
        }
    }

    public void evaluate(PlayerProgress p, Collection<String> questIds, long now, Changes ch) {
        for (String id : questIds) {
            QuestDefinition q = reg.get(id);
            if (q != null) {
                evaluateOne(p, q, now, ch);
            }
        }
    }

    private void evaluateOne(PlayerProgress p, QuestDefinition q, long now, Changes ch) {
        if (!q.enabled || reg.isInvalid(q.id)) {
            return;
        }
        QuestProgress qp = p.quests.get(q.id);
        boolean met = prerequisitesMet(p, q);
        if (qp == null) {
            if (!met) {
                return;
            }
            if (q.acceptance == AcceptanceMode.AUTO) {
                start(p, q, now, ch);
            } else {
                QuestProgress created = new QuestProgress();
                created.status = QuestStatus.AVAILABLE;
                created.since = now;
                p.quests.put(q.id, created);
                p.dirty = true;
                ch.touch(q.id);
                ch.notice(NoticeType.QUEST_AVAILABLE, q.id, "", 0, 0);
            }
        } else if (qp.status == QuestStatus.AVAILABLE) {
            if (!met) {
                p.quests.remove(q.id); // prerequisites changed: lock it again
                p.dirty = true;
                ch.touch(q.id);
            } else if (q.acceptance == AcceptanceMode.AUTO) {
                start(p, q, now, ch);
            }
        }
    }

    private void start(PlayerProgress p, QuestDefinition q, long now, Changes ch) {
        QuestProgress qp = p.quests.computeIfAbsent(q.id, k -> new QuestProgress());
        qp.status = QuestStatus.ACTIVE;
        qp.since = now;
        for (ObjectiveDefinition o : q.objectives) {
            qp.objective(o.id);
        }
        refreshReveal(q, qp, ch);
        p.dirty = true;
        ch.touch(q.id);
        ch.activated.add(q.id);
        ch.indexDirty = true;
        ch.notice(NoticeType.QUEST_STARTED, q.id, "", 0, 0);
        checkCompletion(p, q, qp, now, ch);
    }

    // ================================================================== player actions

    public Result accept(PlayerProgress p, String questId, long now, Changes ch) {
        QuestDefinition q = reg.get(questId);
        if (q == null || !q.enabled) {
            return Result.fail("unknown_quest", "That quest does not exist.");
        }
        if (reg.isInvalid(q.id)) {
            return Result.fail("invalid", "This quest needs attention from a server administrator and cannot be started yet.");
        }
        QuestStatus s = baseStatus(p, q);
        if (s != QuestStatus.AVAILABLE && s != QuestStatus.ABANDONED) {
            return Result.fail("not_available", "This quest is not available.");
        }
        if (!prerequisitesMet(p, q)) {
            return Result.fail("locked", "Complete the required quests first.");
        }
        QuestProgress existing = p.quests.get(q.id);
        for (DialogueSequence d : q.dialogues) {
            if (d.gate == DialogueGate.ACCEPT && (existing == null || !existing.dialoguesDone.contains(d.id))) {
                return Result.fail("dialogue_required", "Read \"" + (d.title.isEmpty() ? d.id : d.title) + "\" before accepting.");
            }
        }
        if (existing != null && s == QuestStatus.ABANDONED && q.abandon == AbandonMode.RESET_PROGRESS) {
            existing.resetProgress(); // "reset when accepted again"
        }
        start(p, q, now, ch);
        return Result.ok();
    }

    public Result abandon(PlayerProgress p, String questId, long now, Changes ch) {
        QuestDefinition q = reg.get(questId);
        QuestProgress qp = p.quests.get(questId);
        if (q == null || qp == null || qp.status != QuestStatus.ACTIVE) {
            return Result.fail("not_active", "Only active quests can be abandoned.");
        }
        if (q.abandon == AbandonMode.DISABLED) {
            return Result.fail("abandon_disabled", "This quest cannot be abandoned.");
        }
        qp.status = QuestStatus.ABANDONED;
        qp.since = now;
        p.dirty = true;
        ch.touch(q.id);
        ch.indexDirty = true;
        return Result.ok();
    }

    public Result markDialogueDone(PlayerProgress p, String questId, String dialogueId, long now, Changes ch) {
        QuestDefinition q = reg.get(questId);
        if (q == null || !q.enabled || q.dialogue(dialogueId) == null) {
            return Result.fail("unknown_dialogue", "That dialogue does not exist.");
        }
        QuestStatus s = statusOf(p, q);
        if (s == QuestStatus.LOCKED || s == QuestStatus.INVALID) {
            return Result.fail("not_visible", "That quest is not available yet.");
        }
        QuestProgress qp = p.quests.computeIfAbsent(q.id, k -> {
            QuestProgress c = new QuestProgress();
            c.status = QuestStatus.AVAILABLE;
            c.since = now;
            return c;
        });
        if (qp.dialoguesDone.add(dialogueId)) {
            p.dirty = true;
            ch.touch(q.id);
        }
        return Result.ok();
    }

    // ================================================================== objective progress

    /** Adds {@code amount} to an objective (kills, pickups, deliveries, block breaks, interactions). */
    public Result advance(PlayerProgress p, QuestDefinition q, int objIndex, int amount, long now, Changes ch) {
        if (amount <= 0) {
            return Result.fail("bad_amount", "Nothing to add.");
        }
        ObjectiveProgress op = progressable(p, q, objIndex);
        if (op == null) {
            return Result.fail("not_progressable", "That objective cannot progress right now.");
        }
        ObjectiveDefinition o = q.objectives.get(objIndex);
        int before = op.count;
        op.count = (int) Math.min((long) o.requiredCount(), (long) op.count + amount);
        return applyProgress(p, q, o, op, before, now, ch);
    }

    /** Raises an objective to at least {@code value} (inventory possession, location reached). Never lowers it. */
    public Result setAtLeast(PlayerProgress p, QuestDefinition q, int objIndex, int value, long now, Changes ch) {
        ObjectiveProgress op = progressable(p, q, objIndex);
        if (op == null) {
            return Result.fail("not_progressable", "That objective cannot progress right now.");
        }
        ObjectiveDefinition o = q.objectives.get(objIndex);
        int before = op.count;
        op.count = Math.max(op.count, Math.min(value, o.requiredCount()));
        if (op.count == before) {
            return Result.fail("no_change", "No change.");
        }
        return applyProgress(p, q, o, op, before, now, ch);
    }

    /** How many more items a delivery objective still wants; 0 when it cannot accept deliveries now. */
    public int deliverRemaining(PlayerProgress p, QuestDefinition q, int objIndex) {
        ObjectiveProgress op = progressable(p, q, objIndex);
        if (op == null) {
            return 0;
        }
        ObjectiveDefinition o = q.objectives.get(objIndex);
        return o.type == com.questjournal.core.model.ObjectiveType.DELIVER_ITEM ? Math.max(0, o.requiredCount() - op.count) : 0;
    }

    /** Returns the saved progress if this objective may currently gain progress, else null. */
    private ObjectiveProgress progressable(PlayerProgress p, QuestDefinition q, int objIndex) {
        QuestProgress qp = p.quests.get(q.id);
        if (qp == null || qp.status != QuestStatus.ACTIVE || reg.isInvalid(q.id)
                || objIndex < 0 || objIndex >= q.objectives.size()) {
            return null;
        }
        ObjectiveDefinition o = q.objectives.get(objIndex);
        if (reg.isObjectiveUnresolved(q.id, o.id)) {
            return null;
        }
        ObjectiveProgress op = qp.objective(o.id);
        if (op.done || (!op.revealed && !o.trackBeforeUnlock)) {
            return null;
        }
        return op;
    }

    private Result applyProgress(PlayerProgress p, QuestDefinition q, ObjectiveDefinition o, ObjectiveProgress op,
                                 int before, long now, Changes ch) {
        QuestProgress qp = p.quests.get(q.id);
        int target = o.requiredCount();
        if (op.count >= target) {
            op.done = true;
            ch.notice(NoticeType.OBJECTIVE_COMPLETE, q.id, o.id, op.count, target);
        } else if (op.count != before) {
            ch.notice(NoticeType.OBJECTIVE_PROGRESS, q.id, o.id, op.count, target);
        }
        p.dirty = true;
        ch.touch(q.id);
        ch.indexDirty = true;
        if (op.done) {
            refreshReveal(q, qp, ch);
            checkCompletion(p, q, qp, now, ch);
        }
        return Result.ok();
    }

    private void refreshReveal(QuestDefinition q, QuestProgress qp, Changes ch) {
        boolean previousRequiredDone = true;
        for (ObjectiveDefinition o : q.objectives) {
            ObjectiveProgress op = qp.objective(o.id);
            if (!op.revealed && (q.objectiveMode == ObjectiveMode.ALL_AT_ONCE || previousRequiredDone)) {
                op.revealed = true;
                ch.activated.add(q.id);
                ch.indexDirty = true;
            }
            if (o.required && !op.done) {
                previousRequiredDone = false;
            }
        }
    }

    private boolean allRequiredDone(QuestDefinition q, QuestProgress qp) {
        for (ObjectiveDefinition o : q.objectives) {
            if (o.required && !qp.objective(o.id).done) {
                return false;
            }
        }
        return true;
    }

    private void checkCompletion(PlayerProgress p, QuestDefinition q, QuestProgress qp, long now, Changes ch) {
        if (qp.status != QuestStatus.ACTIVE || !allRequiredDone(q, qp)) {
            return;
        }
        qp.status = QuestStatus.CLAIMABLE;
        qp.since = now;
        p.dirty = true;
        ch.touch(q.id);
        ch.indexDirty = true;
        ch.notice(NoticeType.QUEST_CLAIMABLE, q.id, "", 0, 0);
    }

    // ================================================================== claiming

    /** Checks everything that does not depend on inventory space or command policy. */
    public Result checkClaim(PlayerProgress p, QuestDefinition q) {
        if (q == null) {
            return Result.fail("unknown_quest", "That quest does not exist.");
        }
        if (reg.isInvalid(q.id)) {
            return Result.fail("invalid", "This quest needs attention from a server administrator before it can be claimed.");
        }
        QuestProgress qp = p.quests.get(q.id);
        if (qp == null || qp.status != QuestStatus.CLAIMABLE) {
            return Result.fail("not_claimable", "This reward cannot be claimed (already claimed or not finished).");
        }
        for (DialogueSequence d : q.dialogues) {
            if (d.gate == DialogueGate.CLAIM && !qp.dialoguesDone.contains(d.id)) {
                return Result.fail("dialogue_required", "Read \"" + (d.title.isEmpty() ? d.id : d.title) + "\" before claiming.");
            }
        }
        return Result.ok();
    }

    /** Rewards that still have to be delivered (those whose id is not yet recorded as granted). */
    public java.util.List<RewardDefinition> pendingRewards(PlayerProgress p, QuestDefinition q) {
        QuestProgress qp = p.quests.get(q.id);
        java.util.List<RewardDefinition> out = new ArrayList<>();
        for (RewardDefinition r : q.rewards) {
            if (qp == null || !qp.grantedRewards.contains(r.id)) {
                out.add(r);
            }
        }
        return out;
    }

    public void markRewardGranted(PlayerProgress p, String questId, String rewardId) {
        QuestProgress qp = p.quests.get(questId);
        if (qp != null && qp.grantedRewards.add(rewardId)) {
            p.dirty = true;
        }
    }

    /** Completes the quest once every reward was delivered. Calling it twice is harmless (second call fails). */
    public Result finishClaim(PlayerProgress p, QuestDefinition q, long now, Changes ch) {
        QuestProgress qp = p.quests.get(q.id);
        if (qp == null || qp.status != QuestStatus.CLAIMABLE) {
            return Result.fail("not_claimable", "This reward cannot be claimed (already claimed or not finished).");
        }
        for (RewardDefinition r : q.rewards) {
            if (!qp.grantedRewards.contains(r.id)) {
                return Result.fail("rewards_pending", "Some rewards have not been delivered yet.");
            }
        }
        qp.status = QuestStatus.COMPLETED;
        qp.since = now;
        p.dirty = true;
        ch.touch(q.id);
        ch.indexDirty = true;
        ch.notice(NoticeType.REWARD_CLAIMED, q.id, "", 0, 0);
        evaluate(p, reg.dependents(q.id), now, ch); // unlock / start the next quests in the chain
        return Result.ok();
    }

    // ================================================================== reconcile (login, reload)

    /**
     * Brings saved progress in line with the current definitions after a reload or login. Progress is addressed by
     * quest id and objective id, so renaming a quest or reordering objectives never resets anything. Objectives whose
     * target count was lowered complete; a claimable quest that gained a new unfinished required objective returns to
     * active (its progress is kept).
     */
    public void reconcile(PlayerProgress p, long now, Changes ch) {
        for (Map.Entry<String, QuestProgress> e : new ArrayList<>(p.quests.entrySet())) {
            QuestDefinition q = reg.get(e.getKey());
            QuestProgress qp = e.getValue();
            if (q == null || (qp.status != QuestStatus.ACTIVE && qp.status != QuestStatus.CLAIMABLE)) {
                continue;
            }
            for (ObjectiveDefinition o : q.objectives) {
                ObjectiveProgress op = qp.objective(o.id);
                if (!op.done && op.count >= o.requiredCount()) {
                    op.done = true;
                    p.dirty = true;
                }
            }
            if (qp.status == QuestStatus.CLAIMABLE && !allRequiredDone(q, qp)) {
                qp.status = QuestStatus.ACTIVE;
                qp.since = now;
                p.dirty = true;
            }
            if (qp.status == QuestStatus.ACTIVE) {
                refreshReveal(q, qp, ch);
                checkCompletion(p, q, qp, now, ch);
            }
            ch.touch(q.id);
            ch.activated.add(q.id);
        }
        evaluateAll(p, now, ch);
        ch.indexDirty = true;
    }

    // ================================================================== index

    public ActiveIndex buildIndex(PlayerProgress p) {
        ActiveIndex idx = new ActiveIndex();
        for (Map.Entry<String, QuestProgress> e : p.quests.entrySet()) {
            QuestProgress qp = e.getValue();
            if (qp.status != QuestStatus.ACTIVE) {
                continue;
            }
            QuestDefinition q = reg.get(e.getKey());
            if (q == null || !q.enabled || reg.isInvalid(q.id)) {
                continue;
            }
            for (int i = 0; i < q.objectives.size(); i++) {
                ObjectiveDefinition o = q.objectives.get(i);
                ObjectiveProgress op = qp.objectives.get(o.id);
                if (op == null || op.done || (!op.revealed && !o.trackBeforeUnlock) || reg.isObjectiveUnresolved(q.id, o.id)) {
                    continue;
                }
                idx.add(o.type, o.target, new ActiveIndex.Ref(q.id, i));
            }
        }
        return idx;
    }

    /** Convenience for tests and the server: counts as an int array in objective order (display values). */
    public int[] counts(PlayerProgress p, QuestDefinition q) {
        int[] out = new int[q.objectives.size()];
        QuestProgress qp = p.quests.get(q.id);
        if (qp != null) {
            for (int i = 0; i < out.length; i++) {
                ObjectiveProgress op = qp.objectives.get(q.objectives.get(i).id);
                out[i] = op == null ? 0 : Math.min(op.count, q.objectives.get(i).requiredCount());
            }
        }
        return out;
    }

    /** Objective ids of the quest that are currently visible to the player (sequential quests hide later ones). */
    public Set<String> visibleObjectiveIds(PlayerProgress p, QuestDefinition q) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        QuestProgress qp = p.quests.get(q.id);
        for (ObjectiveDefinition o : q.objectives) {
            ObjectiveProgress op = qp == null ? null : qp.objectives.get(o.id);
            if (q.objectiveMode == ObjectiveMode.ALL_AT_ONCE || (op != null && op.revealed)) {
                ids.add(o.id);
            }
        }
        return ids;
    }
}
