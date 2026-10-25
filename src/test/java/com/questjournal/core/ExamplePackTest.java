package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestCategory;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.pack.PackLoader;
import com.questjournal.core.registry.QuestRegistry;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.RegistryChecker;
import com.questjournal.core.validate.ValidationReport;

/** The shipped example pack must load cleanly on a vanilla installation (no missing-mod warnings). */
class ExamplePackTest {
    static final Path PACK = Paths.get("src/main/resources/examples/questjournal_examples");

    /** Vanilla 1.20.1 ids that the example pack is allowed to use (checked by hand against the vanilla registries). */
    static final Set<String> VANILLA = Set.of("minecraft:oak_log", "minecraft:bread", "minecraft:wooden_pickaxe", "minecraft:stone",
            "minecraft:cobblestone", "minecraft:stone_pickaxe", "minecraft:iron_ingot", "minecraft:coal", "minecraft:iron_pickaxe",
            "minecraft:golden_apple", "minecraft:the_nether", "minecraft:obsidian", "minecraft:lever", "minecraft:wheat",
            "minecraft:hay_block", "minecraft:overworld", "minecraft:compass", "minecraft:the_end", "minecraft:ender_eye",
            "minecraft:ender_pearl", "minecraft:wither", "minecraft:nether_star", "minecraft:ender_dragon", "minecraft:elytra",
            "minecraft:dragon_head", "minecraft:book");

    @Test
    void loadsWithoutErrorsAndCoversTheFeatureSet() throws Exception {
        PackLoader.PackOutcome out = PackLoader.loadPack(PACK, "questjournal_examples", new HashMap<>(), null, 1 << 20);
        assertFalse(out.hasErrors(), out.issues.toString());
        assertEquals(0, out.failedFiles);
        List<QuestDefinition> quests = out.quests.stream().map(q -> q.def()).collect(Collectors.toList());
        assertEquals(10, quests.size());

        Set<QuestCategory> cats = EnumSet.noneOf(QuestCategory.class);
        Set<ObjectiveType> types = EnumSet.noneOf(ObjectiveType.class);
        Set<RewardType> rewards = EnumSet.noneOf(RewardType.class);
        for (QuestDefinition q : quests) {
            cats.add(q.category);
            for (ObjectiveDefinition o : q.objectives) {
                types.add(o.type);
                assertTrue(VANILLA.contains(o.target), "unexpected id " + o.target);
            }
            for (RewardDefinition r : q.rewards) {
                rewards.add(r.type);
                if (r.type == RewardType.ITEM) {
                    assertTrue(VANILLA.contains(r.item) || r.item.equals("minecraft:ender_pearl"), "unexpected id " + r.item);
                }
            }
            if (!q.icon.isEmpty()) {
                assertTrue(VANILLA.contains(q.icon), "unexpected icon " + q.icon);
            }
        }
        assertEquals(EnumSet.allOf(QuestCategory.class), cats, "all four categories are demonstrated");
        assertEquals(EnumSet.allOf(ObjectiveType.class), types, "every objective type is demonstrated");
        assertTrue(rewards.contains(RewardType.ITEM) && rewards.contains(RewardType.EXPERIENCE));
        assertFalse(rewards.contains(RewardType.COMMAND), "the default pack contains no command rewards");

        RegistryChecker vanilla = new RegistryChecker() {
            @Override
            public boolean exists(com.questjournal.core.model.RegistryKind kind, String id) {
                return VANILLA.contains(id);
            }
        };
        ValidationReport rep = QuestValidator.validate(quests, vanilla, null, null);
        assertEquals(0, rep.issues().size(), rep.issues().toString());
        QuestRegistry reg = QuestRegistry.build(1, quests, new HashMap<>(), rep);
        for (QuestDefinition q : quests) {
            assertFalse(reg.isInvalid(q.id));
        }
    }

    @Test
    void indexListsExactlyTheFilesOfThePack() throws Exception {
        Path index = Paths.get("src/main/resources/examples/index.txt");
        List<String> listed = Files.readAllLines(index, StandardCharsets.UTF_8).stream().map(String::trim).filter(s -> !s.isEmpty()).sorted().collect(Collectors.toList());
        List<String> actual;
        try (Stream<Path> s = Files.walk(PACK)) {
            actual = s.filter(Files::isRegularFile).map(p -> PACK.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
        }
        assertEquals(actual, listed, "src/main/resources/examples/index.txt must list every file of the example pack");
    }

    @Test
    void documentedModdedExampleParsesAndShowsMissingModBehaviourOnAVanillaInstall() {
        PackLoader.PackOutcome out = PackLoader.loadPack(Paths.get("examples/modded_content_example"), "modded_content_example", new HashMap<>(), null, 1 << 20);
        assertFalse(out.hasErrors(), out.issues.toString());
        assertEquals(3, out.quests.size());
        List<QuestDefinition> quests = out.quests.stream().map(q -> q.def()).collect(Collectors.toList());
        RegistryChecker vanillaOnly = new RegistryChecker() {
            @Override
            public boolean exists(com.questjournal.core.model.RegistryKind kind, String id) {
                return id.startsWith("minecraft:");
            }

            @Override
            public boolean namespaceLoaded(String ns) {
                return ns.equals("minecraft");
            }
        };
        QuestRegistry reg = QuestRegistry.build(1, quests, new HashMap<>(), QuestValidator.validate(quests, vanillaOnly, null, null));
        assertEquals(3, reg.size(), "nothing is deleted");
        assertTrue(reg.isInvalid("superhero:recover_the_artifact"));
        assertTrue(reg.isInvalid("superhero:final_boss"));
        assertFalse(reg.isInvalid("superhero:intro"), "the vanilla quest next to them keeps working");
    }

    @Test
    void commandRewardExampleParsesAndIsFlaggedUntilApproved() {
        PackLoader.PackOutcome out = PackLoader.loadPack(Paths.get("examples/command_reward_example"), "command_reward_example", new HashMap<>(), null, 1 << 20);
        assertFalse(out.hasErrors(), out.issues.toString());
        QuestDefinition q = out.quests.get(0).def();
        assertTrue(q.hasCommandRewards());
        com.questjournal.core.security.CommandPolicy policy = new com.questjournal.core.security.CommandPolicy(true, List.of("give"), false);
        assertTrue(policy.check(q.rewards.get(0).command).isEmpty(), "the example command passes the safety policy");
        ValidationReport rep = QuestValidator.validate(List.of(q), null, policy, fp -> false);
        assertTrue(rep.hasCode("command_unapproved"));
    }
}
