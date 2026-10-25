package com.questjournal.core;

import static com.questjournal.core.TestData.itemReward;
import static com.questjournal.core.TestData.obj;
import static com.questjournal.core.TestData.quest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.questjournal.core.TestData.Env;
import com.questjournal.core.model.AbandonMode;
import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.DialogueGate;
import com.questjournal.core.model.DialogueLine;
import com.questjournal.core.model.DialogueSequence;
import com.questjournal.core.model.ObjectiveMode;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.PrerequisiteMode;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.Visibility;
import com.questjournal.core.state.ActiveIndex;
import com.questjournal.core.state.Changes;
import com.questjournal.core.state.NoticeType;
import com.questjournal.core.state.ProgressMath;
import com.questjournal.core.state.Result;

class ProgressEngineTest {
    private static QuestDefinition killQuest(String id, AcceptanceMode mode, int count, String... prereqs) {
        QuestDefinition q = quest(id, mode, prereqs);
        obj(q, "kill", ObjectiveType.KILL_ENTITY, "minecraft:zombie", count);
        return q;
    }

    private static boolean hasNotice(Changes ch, NoticeType t) {
        return ch.notices.stream().anyMatch(n -> n.type() == t);
    }

    // ---------------------------------------------------------------- acceptance behaviours

    @Test
    void manualQuestAppearsAvailableAndOnlyProgressesAfterAcceptance() {
        QuestDefinition q = killQuest("t:manual", AcceptanceMode.MANUAL, 3);
        Env e = new Env(q);
        e.eval();
        assertEquals(QuestStatus.AVAILABLE, e.status("t:manual"));
        assertTrue(hasNotice(e.ch, NoticeType.QUEST_AVAILABLE));
        assertFalse(e.adv("t:manual", 0, 1).success(), "no progress before accepting");
        assertTrue(e.accept("t:manual").success());
        assertEquals(QuestStatus.ACTIVE, e.status("t:manual"));
        assertTrue(e.adv("t:manual", 0, 1).success());
        assertEquals(1, e.eng.counts(e.p, q)[0]);
    }

    @Test
    void autoQuestStartsWithoutAnExtraClick() {
        QuestDefinition q = killQuest("t:auto", AcceptanceMode.AUTO, 1);
        Env e = new Env(q);
        e.eval();
        assertEquals(QuestStatus.ACTIVE, e.status("t:auto"));
        assertTrue(hasNotice(e.ch, NoticeType.QUEST_STARTED));
        assertTrue(e.ch.activated.contains("t:auto"));
    }

    @Test
    void prerequisiteGatedChainUnlocksStepByStep() {
        QuestDefinition a = killQuest("t:a", AcceptanceMode.AUTO, 1);
        QuestDefinition b = killQuest("t:b", AcceptanceMode.MANUAL, 1, "t:a");
        QuestDefinition c = killQuest("t:c", AcceptanceMode.AUTO, 1, "t:b");
        QuestDefinition boss = killQuest("t:boss", AcceptanceMode.MANUAL, 1, "t:c");
        Env e = new Env(a, b, c, boss);
        e.eval();
        assertEquals(QuestStatus.ACTIVE, e.status("t:a"));
        assertEquals(QuestStatus.LOCKED, e.status("t:b"));
        assertFalse(e.accept("t:b").success(), "locked quests cannot be accepted");

        e.adv("t:a", 0, 1);
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:a"));
        assertEquals(QuestStatus.LOCKED, e.status("t:b"), "claimable is not completed: the chain waits for the claim");
        assertTrue(e.claim("t:a").success());
        assertEquals(QuestStatus.AVAILABLE, e.status("t:b"));
        assertEquals(QuestStatus.LOCKED, e.status("t:c"));

        e.accept("t:b");
        e.adv("t:b", 0, 1);
        e.claim("t:b");
        assertEquals(QuestStatus.ACTIVE, e.status("t:c"), "auto quest starts by itself once unlocked");
        e.adv("t:c", 0, 1);
        e.claim("t:c");
        assertEquals(QuestStatus.AVAILABLE, e.status("t:boss"));
    }

    @Test
    void anyModeNeedsOnlyOnePrerequisite() {
        QuestDefinition a = killQuest("t:a", AcceptanceMode.AUTO, 1);
        QuestDefinition b = killQuest("t:b", AcceptanceMode.AUTO, 1);
        QuestDefinition c = killQuest("t:c", AcceptanceMode.MANUAL, 1, "t:a", "t:b");
        c.prerequisiteMode = PrerequisiteMode.ANY;
        Env e = new Env(a, b, c);
        e.eval();
        assertEquals(QuestStatus.LOCKED, e.status("t:c"));
        e.adv("t:b", 0, 1);
        e.claim("t:b");
        assertEquals(QuestStatus.AVAILABLE, e.status("t:c"));
    }

    // ---------------------------------------------------------------- objectives

    @Test
    void sequentialObjectivesRevealOneAfterAnotherAndDoNotGainEarlyProgress() {
        QuestDefinition q = quest("t:seq", AcceptanceMode.AUTO);
        q.objectiveMode = ObjectiveMode.SEQUENTIAL;
        obj(q, "one", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 2);
        obj(q, "two", ObjectiveType.OBTAIN_ITEM, "minecraft:diamond", 1);
        obj(q, "three", ObjectiveType.BREAK_BLOCK, "minecraft:stone", 3).trackBeforeUnlock = true;
        Env e = new Env(q);
        e.eval();
        assertEquals(Set.of("one"), e.eng.visibleObjectiveIds(e.p, q));
        assertFalse(e.adv("t:seq", 1, 1).success(), "objective two is not unlocked yet");
        assertEquals(0, e.eng.counts(e.p, q)[1]);
        assertTrue(e.adv("t:seq", 2, 1).success(), "explicit track_before_unlock allows early progress");
        e.adv("t:seq", 0, 2);
        assertEquals(Set.of("one", "two"), e.eng.visibleObjectiveIds(e.p, q));
        assertEquals(QuestStatus.ACTIVE, e.status("t:seq"));
        e.adv("t:seq", 1, 1);
        e.adv("t:seq", 2, 2);
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:seq"));
    }

    @Test
    void allAtOnceRevealsEverythingAndOptionalObjectivesDoNotBlockCompletion() {
        QuestDefinition q = quest("t:all", AcceptanceMode.AUTO);
        obj(q, "req", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 1);
        obj(q, "opt", ObjectiveType.KILL_ENTITY, "minecraft:creeper", 5).required = false;
        Env e = new Env(q);
        e.eval();
        assertEquals(2, e.eng.visibleObjectiveIds(e.p, q).size());
        e.adv("t:all", 0, 1);
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:all"));
        assertTrue(hasNotice(e.ch, NoticeType.QUEST_CLAIMABLE));
    }

    @Test
    void progressIsCappedAndCompletionNoticeIsEmittedOnce() {
        QuestDefinition q = killQuest("t:cap", AcceptanceMode.AUTO, 3);
        Env e = new Env(q);
        e.eval();
        e.adv("t:cap", 0, 100);
        assertEquals(3, e.eng.counts(e.p, q)[0]);
        assertFalse(e.adv("t:cap", 0, 1).success(), "finished objectives take no more progress");
        assertEquals(1, e.ch.notices.stream().filter(n -> n.type() == NoticeType.OBJECTIVE_COMPLETE).count());
    }

    @Test
    void haveObjectiveOnlyEverRaisesCountAndLatches() {
        QuestDefinition q = quest("t:have", AcceptanceMode.AUTO);
        obj(q, "gun", ObjectiveType.HAVE_ITEM, "minecraft:diamond", 3);
        Env e = new Env(q);
        e.eval();
        assertTrue(e.eng.setAtLeast(e.p, q, 0, 2, 1, e.ch).success());
        assertFalse(e.eng.setAtLeast(e.p, q, 0, 1, 2, e.ch).success(), "never lowers");
        assertEquals(2, e.eng.counts(e.p, q)[0]);
        e.eng.setAtLeast(e.p, q, 0, 9, 3, e.ch);
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:have"));
    }

    @Test
    void deliveryRemainingTracksWhatIsStillNeeded() {
        QuestDefinition q = quest("t:del", AcceptanceMode.AUTO);
        obj(q, "coal", ObjectiveType.DELIVER_ITEM, "minecraft:coal", 5);
        obj(q, "kills", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 5);
        Env e = new Env(q);
        e.eval();
        assertEquals(5, e.eng.deliverRemaining(e.p, q, 0));
        assertEquals(0, e.eng.deliverRemaining(e.p, q, 1), "only delivery objectives accept submissions");
        e.adv("t:del", 0, 3);
        assertEquals(2, e.eng.deliverRemaining(e.p, q, 0));
        e.adv("t:del", 0, 2);
        assertEquals(0, e.eng.deliverRemaining(e.p, q, 0), "a finished delivery cannot be submitted again");
    }

    // ---------------------------------------------------------------- abandon

    @Test
    void abandonCanPreserveProgress() {
        QuestDefinition q = killQuest("t:ab", AcceptanceMode.MANUAL, 5);
        q.abandon = AbandonMode.PRESERVE_PROGRESS;
        Env e = new Env(q);
        e.eval();
        e.accept("t:ab");
        e.adv("t:ab", 0, 2);
        assertTrue(e.eng.abandon(e.p, "t:ab", 5000, e.ch).success());
        assertEquals(QuestStatus.ABANDONED, e.status("t:ab"));
        assertFalse(e.adv("t:ab", 0, 1).success(), "no progress while abandoned");
        assertTrue(e.accept("t:ab").success());
        assertEquals(2, e.eng.counts(e.p, q)[0], "progress resumed");
    }

    @Test
    void abandonCanResetProgressWhenAcceptedAgain() {
        QuestDefinition q = killQuest("t:ab", AcceptanceMode.AUTO, 5);
        q.abandon = AbandonMode.RESET_PROGRESS;
        Env e = new Env(q);
        e.eval();
        e.adv("t:ab", 0, 4);
        e.eng.abandon(e.p, "t:ab", 5000, e.ch);
        e.eval();
        assertEquals(QuestStatus.ABANDONED, e.status("t:ab"), "an auto quest does not restart by itself after abandoning");
        assertTrue(e.accept("t:ab").success());
        assertEquals(0, e.eng.counts(e.p, q)[0]);
    }

    @Test
    void abandonCanBeDisabledAndNeverTouchesTheDefinition() {
        QuestDefinition q = killQuest("t:ab", AcceptanceMode.AUTO, 5);
        q.abandon = AbandonMode.DISABLED;
        Env e = new Env(q);
        e.eval();
        assertFalse(e.eng.abandon(e.p, "t:ab", 5000, e.ch).success());
        assertNotNull(e.reg.get("t:ab"));
    }

    // ---------------------------------------------------------------- claiming

    @Test
    void claimIsIdempotentAndNeverDuplicatesRewards() {
        QuestDefinition q = killQuest("t:claim", AcceptanceMode.AUTO, 1);
        itemReward(q, "r1", "minecraft:diamond", 3);
        itemReward(q, "r2", "minecraft:emerald", 2);
        Env e = new Env(q);
        e.eval();
        assertFalse(e.eng.checkClaim(e.p, q).success(), "not claimable while active");
        e.adv("t:claim", 0, 1);
        assertTrue(e.eng.checkClaim(e.p, q).success());
        assertEquals(2, e.eng.pendingRewards(e.p, q).size());

        // reward 1 is delivered, then reward 2 fails (e.g. command error): the quest must not complete
        e.eng.markRewardGranted(e.p, "t:claim", "r1");
        assertFalse(e.eng.finishClaim(e.p, q, 9000, e.ch).success());
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:claim"));
        assertEquals(List.of("r2"), e.eng.pendingRewards(e.p, q).stream().map(r -> r.id).toList(), "a retry only delivers what is missing");

        e.eng.markRewardGranted(e.p, "t:claim", "r2");
        assertTrue(e.eng.finishClaim(e.p, q, 9001, e.ch).success());
        assertEquals(QuestStatus.COMPLETED, e.status("t:claim"));
        assertTrue(hasNotice(e.ch, NoticeType.REWARD_CLAIMED));

        // duplicate packet / malicious client: everything is refused now
        assertFalse(e.eng.finishClaim(e.p, q, 9002, e.ch).success());
        assertFalse(e.eng.checkClaim(e.p, q).success());
    }

    @Test
    void dialogueGatesBlockAcceptAndClaimUntilRead() {
        QuestDefinition q = killQuest("t:dlg", AcceptanceMode.MANUAL, 1);
        DialogueSequence before = new DialogueSequence();
        before.id = "intro";
        before.gate = DialogueGate.ACCEPT;
        before.lines.add(new DialogueLine());
        DialogueSequence after = new DialogueSequence();
        after.id = "outro";
        after.gate = DialogueGate.CLAIM;
        after.lines.add(new DialogueLine());
        q.dialogues.add(before);
        q.dialogues.add(after);
        Env e = new Env(q);
        e.eval();
        assertEquals("dialogue_required", e.accept("t:dlg").code());
        assertTrue(e.eng.markDialogueDone(e.p, "t:dlg", "intro", 1, e.ch).success());
        assertTrue(e.accept("t:dlg").success());
        e.adv("t:dlg", 0, 1);
        assertEquals("dialogue_required", e.eng.checkClaim(e.p, q).code());
        e.eng.markDialogueDone(e.p, "t:dlg", "outro", 2, e.ch);
        assertTrue(e.eng.checkClaim(e.p, q).success());
        assertFalse(e.eng.markDialogueDone(e.p, "t:dlg", "nope", 3, e.ch).success());
    }

    // ---------------------------------------------------------------- invalid quests / missing mods

    @Test
    void progressIsFrozenWhileContentIsMissingAndResumesAfterItReturns() {
        QuestDefinition q = quest("t:mod", AcceptanceMode.AUTO);
        obj(q, "rifle", ObjectiveType.OBTAIN_ITEM, "exampleguns:assault_rifle", 2);
        Env e = new Env(q);
        e.use(TestData.registry(new TestData.FakeRegistry().with("exampleguns:assault_rifle"), q));
        e.eval();
        e.adv("t:mod", 0, 1);
        assertEquals(1, e.eng.counts(e.p, q)[0]);

        e.use(TestData.registry(new TestData.FakeRegistry(), q)); // mod removed + /questjournal reload
        assertEquals(QuestStatus.INVALID, e.status("t:mod"));
        assertFalse(e.adv("t:mod", 0, 1).success(), "unresolved objectives can never complete");
        assertFalse(e.eng.checkClaim(e.p, q).success());
        assertFalse(e.accept("t:mod").success());
        assertEquals(1, e.eng.counts(e.p, q)[0], "saved progress is untouched");
        assertTrue(e.eng.buildIndex(e.p).isEmpty(), "and nothing listens for it");

        e.use(TestData.registry(new TestData.FakeRegistry().with("exampleguns:assault_rifle"), q)); // mod is back
        assertEquals(QuestStatus.ACTIVE, e.status("t:mod"));
        assertTrue(e.adv("t:mod", 0, 1).success());
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:mod"));
    }

    // ---------------------------------------------------------------- reload / migration by stable ids

    @Test
    void editingTitlesDescriptionsAndObjectiveOrderKeepsProgress() {
        QuestDefinition v1 = quest("t:edit", AcceptanceMode.AUTO);
        obj(v1, "a", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 10);
        obj(v1, "b", ObjectiveType.KILL_ENTITY, "minecraft:creeper", 4);
        Env e = new Env(v1);
        e.eval();
        e.adv("t:edit", 0, 7);
        e.adv("t:edit", 1, 2);

        QuestDefinition v2 = v1.copy();
        v2.title = "A completely new title";
        v2.description = "New story";
        v2.objectives.add(0, v2.objectives.remove(1)); // reorder: b first
        e.use(TestData.registry(v2));
        e.eng.reconcile(e.p, 5000, e.ch);
        assertEquals(QuestStatus.ACTIVE, e.status("t:edit"));
        assertEquals(List.of(2, 7), List.of(e.eng.counts(e.p, v2)[0], e.eng.counts(e.p, v2)[1]), "counts follow objective ids, not positions");
    }

    @Test
    void loweringATargetCompletesAndAddingARequiredObjectiveReopensAClaimableQuest() {
        QuestDefinition v1 = killQuest("t:mig", AcceptanceMode.AUTO, 10);
        Env e = new Env(v1);
        e.eval();
        e.adv("t:mig", 0, 7);

        QuestDefinition lowered = v1.copy();
        lowered.objectives.get(0).count = 5;
        e.use(TestData.registry(lowered));
        e.eng.reconcile(e.p, 6000, e.ch);
        assertEquals(QuestStatus.CLAIMABLE, e.status("t:mig"));

        QuestDefinition extended = lowered.copy();
        obj(extended, "extra", ObjectiveType.BREAK_BLOCK, "minecraft:stone", 3);
        e.use(TestData.registry(extended));
        e.eng.reconcile(e.p, 7000, e.ch);
        assertEquals(QuestStatus.ACTIVE, e.status("t:mig"), "new required work reopens the quest");
        assertEquals(5, e.eng.counts(e.p, extended)[0], "existing progress is preserved");
    }

    @Test
    void progressForRemovedQuestsIsKeptSoItReturnsIfTheQuestComesBack() {
        QuestDefinition q = killQuest("t:gone", AcceptanceMode.AUTO, 5);
        Env e = new Env(q);
        e.eval();
        e.adv("t:gone", 0, 3);
        e.use(TestData.registry(killQuest("t:other", AcceptanceMode.AUTO, 1)));
        e.eng.reconcile(e.p, 5000, e.ch);
        assertNotNull(e.p.get("t:gone"), "entry is never deleted");
        e.use(TestData.registry(q));
        e.eng.reconcile(e.p, 6000, e.ch);
        assertEquals(3, e.eng.counts(e.p, q)[0]);
    }

    @Test
    void availableQuestRelocksWhenPrerequisitesChange() {
        QuestDefinition a = killQuest("t:a", AcceptanceMode.AUTO, 1);
        QuestDefinition b = killQuest("t:b", AcceptanceMode.MANUAL, 1);
        Env e = new Env(a, b);
        e.eval();
        assertEquals(QuestStatus.AVAILABLE, e.status("t:b"));
        QuestDefinition b2 = b.copy();
        b2.prerequisites.add("t:a");
        e.use(TestData.registry(a, b2));
        e.eng.reconcile(e.p, 5000, e.ch);
        assertEquals(QuestStatus.LOCKED, e.status("t:b"));
        assertNull(e.p.get("t:b"));
    }

    // ---------------------------------------------------------------- index / visibility / math

    @Test
    void activeIndexListsOnlyObjectivesThatCanProgress() {
        QuestDefinition q = quest("t:idx", AcceptanceMode.AUTO);
        q.objectiveMode = ObjectiveMode.SEQUENTIAL;
        obj(q, "k", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 1);
        obj(q, "b", ObjectiveType.BREAK_BLOCK, "minecraft:stone", 1);
        QuestDefinition other = killQuest("t:other", AcceptanceMode.MANUAL, 1);
        Env e = new Env(q, other);
        e.eval();
        ActiveIndex idx = e.eng.buildIndex(e.p);
        assertEquals(1, idx.get(ObjectiveType.KILL_ENTITY, "minecraft:zombie").size());
        assertTrue(idx.get(ObjectiveType.KILL_ENTITY, "minecraft:creeper").isEmpty());
        assertFalse(idx.has(ObjectiveType.BREAK_BLOCK), "sequential objective is not revealed yet");
        e.adv("t:idx", 0, 1);
        idx = e.eng.buildIndex(e.p);
        assertFalse(idx.has(ObjectiveType.KILL_ENTITY), "completed objectives leave the index");
        assertTrue(idx.has(ObjectiveType.BREAK_BLOCK));
    }

    @Test
    void visibilityModesHideQuestsUntilTheyMatter() {
        QuestDefinition a = killQuest("t:a", AcceptanceMode.AUTO, 1);
        QuestDefinition secret = killQuest("t:secret", AcceptanceMode.MANUAL, 1, "t:a");
        secret.visibility = Visibility.WHEN_AVAILABLE;
        QuestDefinition hidden = killQuest("t:hidden", AcceptanceMode.MANUAL, 1, "t:a");
        hidden.visibility = Visibility.WHEN_ACTIVE;
        QuestDefinition off = killQuest("t:off", AcceptanceMode.AUTO, 1);
        off.enabled = false;
        Env e = new Env(a, secret, hidden, off);
        e.eval();
        assertTrue(e.eng.isVisible(e.p, a));
        assertFalse(e.eng.isVisible(e.p, secret));
        assertFalse(e.eng.isVisible(e.p, off));
        e.adv("t:a", 0, 1);
        e.claim("t:a");
        assertTrue(e.eng.isVisible(e.p, secret));
        assertFalse(e.eng.isVisible(e.p, hidden), "needs to be started first");
        e.accept("t:hidden");
        assertTrue(e.eng.isVisible(e.p, hidden));
    }

    @Test
    void progressFractionUsesRequiredObjectivesOnly() {
        QuestDefinition q = quest("t:f", AcceptanceMode.AUTO);
        obj(q, "a", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 4);
        obj(q, "b", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 2);
        obj(q, "c", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 2).required = false;
        assertEquals(0.5f, ProgressMath.fraction(q, QuestStatus.ACTIVE, new int[]{2, 1, 0}), 0.0001);
        assertEquals(1f, ProgressMath.fraction(q, QuestStatus.CLAIMABLE, new int[]{0, 0, 0}), 0.0001);
    }

    @Test
    void resultFactoryAndUnknownQuestHandling() {
        Env e = new Env(killQuest("t:x", AcceptanceMode.AUTO, 1));
        Result r = e.accept("t:does_not_exist");
        assertFalse(r.success());
        assertEquals("unknown_quest", r.code());
        assertTrue(Result.ok().success());
    }
}
