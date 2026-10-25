package com.questjournal.core;

import static com.questjournal.core.TestData.obj;
import static com.questjournal.core.TestData.quest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.registry.QuestRegistry;
import com.questjournal.core.security.CommandPolicy;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.ValidationReport;

class QuestValidatorTest {
    @Test
    void longChainWithBossIsValid() {
        QuestDefinition a = quest("t:a", AcceptanceMode.AUTO);
        QuestDefinition b = quest("t:b", AcceptanceMode.MANUAL, "t:a");
        QuestDefinition c = quest("t:c", AcceptanceMode.MANUAL, "t:b");
        QuestDefinition boss = quest("t:boss", AcceptanceMode.MANUAL, "t:c");
        ValidationReport r = QuestValidator.validate(List.of(a, b, c, boss), new TestData.FakeRegistry(), null, null);
        assertEquals(0, r.issues().size(), r.issues().toString());
    }

    @Test
    void unknownPrerequisiteBlocksTheQuest() {
        QuestDefinition a = quest("t:a", AcceptanceMode.AUTO, "t:ghost");
        QuestRegistry reg = TestData.registry(a);
        assertTrue(reg.isInvalid("t:a"));
        assertEquals("unknown_prerequisite", reg.blockers("t:a").get(0).code());
    }

    @Test
    void circularChainIsDetectedAndEveryMemberReported() {
        QuestDefinition a = quest("t:a", AcceptanceMode.AUTO, "t:c");
        QuestDefinition b = quest("t:b", AcceptanceMode.AUTO, "t:a");
        QuestDefinition c = quest("t:c", AcceptanceMode.AUTO, "t:b");
        QuestDefinition downstream = quest("t:d", AcceptanceMode.AUTO, "t:c");
        QuestDefinition healthy = quest("t:ok", AcceptanceMode.AUTO);
        QuestRegistry reg = TestData.registry(a, b, c, downstream, healthy);
        for (String id : List.of("t:a", "t:b", "t:c")) {
            assertTrue(reg.isInvalid(id), id + " is part of the cycle");
            assertEquals("prerequisite_cycle", reg.blockers(id).get(0).code());
            assertTrue(reg.blockers(id).get(0).message().contains("Circular"));
        }
        assertFalse(reg.isInvalid("t:ok"));
        assertFalse(reg.isInvalid("t:d"), "a dependent of a cycle is not itself broken");
        assertTrue(reg.report.hasCode("prerequisite_blocked"), "but the admin is told it will stay locked");
    }

    @Test
    void selfLoopIsACycle() {
        QuestDefinition a = quest("t:a", AcceptanceMode.AUTO, "t:a");
        assertTrue(TestData.registry(a).isInvalid("t:a"));
    }

    @Test
    void veryLongChainDoesNotOverflowTheStack() {
        List<QuestDefinition> chain = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            chain.add(i == 0 ? quest("t:q0", AcceptanceMode.AUTO) : quest("t:q" + i, AcceptanceMode.AUTO, "t:q" + (i - 1)));
        }
        ValidationReport r = QuestValidator.validate(chain, null, null, null);
        assertEquals(0, r.issues().size());
    }

    @Test
    void missingModContentIsPreservedBlockedAndExplained() {
        QuestDefinition q = quest("t:gun", AcceptanceMode.MANUAL);
        obj(q, "rifle", ObjectiveType.OBTAIN_ITEM, "exampleguns:assault_rifle", 1);
        obj(q, "bonus", ObjectiveType.KILL_ENTITY, "examplealiens:xeno", 1).required = false;
        TestData.itemReward(q, "r1", "exampleguns:ammo", 30);
        q.icon = "exampleguns:icon";
        QuestRegistry reg = TestData.registry(new TestData.FakeRegistry(), q);

        assertTrue(reg.contains("t:gun"), "the definition is kept, not deleted");
        assertTrue(reg.isInvalid("t:gun"));
        assertTrue(reg.isObjectiveUnresolved("t:gun", "rifle"));
        assertTrue(reg.isObjectiveUnresolved("t:gun", "bonus"));
        String text = reg.report.toLines(20).toString();
        assertTrue(text.contains("exampleguns:assault_rifle"));
        assertTrue(text.contains("not installed"), "names the missing mod");
        assertTrue(text.contains("never replaced"), "reward is not substituted");
        assertEquals(q.rewards.get(0).item, "exampleguns:ammo", "reward untouched");

        // The mod comes back after a reload: everything is valid again without touching the definition.
        QuestRegistry back = TestData.registry(new TestData.FakeRegistry().with(
                "exampleguns:assault_rifle", "examplealiens:xeno", "exampleguns:ammo", "exampleguns:icon"), q);
        assertFalse(back.isInvalid("t:gun"));
        assertFalse(back.isObjectiveUnresolved("t:gun", "rifle"));
        assertEquals(0, back.report.issues().size());
    }

    @Test
    void optionalUnresolvedObjectiveDoesNotInvalidateTheQuest() {
        QuestDefinition q = quest("t:opt", AcceptanceMode.AUTO);
        obj(q, "main", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 1);
        obj(q, "bonus", ObjectiveType.KILL_ENTITY, "examplealiens:xeno", 1).required = false;
        QuestRegistry reg = TestData.registry(new TestData.FakeRegistry(), q);
        assertFalse(reg.isInvalid("t:opt"));
        assertTrue(reg.isObjectiveUnresolved("t:opt", "bonus"));
        assertFalse(reg.isObjectiveUnresolved("t:opt", "main"));
    }

    @Test
    void ordinaryQuestsSurviveNextToBrokenOnes() {
        QuestDefinition good = quest("t:good", AcceptanceMode.AUTO);
        obj(good, "z", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 3);
        QuestDefinition bad = quest("t:bad", AcceptanceMode.AUTO);
        obj(bad, "x", ObjectiveType.KILL_ENTITY, "gonemod:thing", 1);
        QuestRegistry reg = TestData.registry(new TestData.FakeRegistry(), good, bad);
        assertEquals(2, reg.size());
        assertFalse(reg.isInvalid("t:good"));
        assertTrue(reg.isInvalid("t:bad"));
    }

    @Test
    void commandRewardsAreFlaggedUntilApprovedAndPolicyChecked() {
        QuestDefinition q = quest("t:cmd", AcceptanceMode.AUTO);
        RewardDefinition r = new RewardDefinition();
        r.id = "r1";
        r.type = RewardType.COMMAND;
        r.command = "op {player}";
        q.rewards.add(r);
        CommandPolicy policy = new CommandPolicy(true, List.of("give", "op"), false);
        ValidationReport rep = QuestValidator.validate(List.of(q), null, policy, fp -> false);
        assertTrue(rep.hasCode("command_not_allowed"), "op is hard-denied even when allowlisted");
        assertTrue(rep.hasCode("command_unapproved"));
        for (Issue i : rep.issues()) {
            assertFalse(i.blocking(), "advisory only: the other rewards stay claimable");
        }
        r.command = "give {player} minecraft:apple 1";
        String fp = CommandPolicy.fingerprint("t:cmd", "r1", r.command);
        ValidationReport ok = QuestValidator.validate(List.of(q), null, policy, fp::equals);
        assertEquals(0, ok.issues().size(), ok.issues().toString());
    }

    @Test
    void registryExposesDependentsAndOrdering() {
        QuestDefinition a = quest("t:a", AcceptanceMode.AUTO);
        QuestDefinition b = quest("t:b", AcceptanceMode.AUTO, "t:a");
        QuestRegistry reg = QuestRegistry.build(3, List.of(b, a), Map.of("t:a", "p"), new ValidationReport());
        assertEquals(List.of("t:b"), reg.dependents("t:a"));
        assertEquals("p", reg.packOf("t:a"));
        assertEquals(3, reg.generation);
    }
}
