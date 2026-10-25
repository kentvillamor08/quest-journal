package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestCategory;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.util.Ids;
import com.questjournal.core.validate.Issue;

class QuestCodecTest {
    static final String FULL = "{\n"
            + "  \"schema_version\": 1, \"id\": \"superhero:recover_the_artifact\", \"title\": \"Recover the Artifact\",\n"
            + "  \"short_description\": \"Short\", \"description\": \"Long story\", \"category\": \"boss_quests\",\n"
            + "  \"icon\": \"minecraft:nether_star\", \"acceptance\": \"manual\", \"visibility\": \"when_available\",\n"
            + "  \"prerequisites\": [\"superhero:intro\"], \"prerequisite_mode\": \"any\", \"objective_mode\": \"sequential\",\n"
            + "  \"abandon\": \"reset_progress\",\n"
            + "  \"objectives\": [\n"
            + "    {\"id\":\"k\",\"type\":\"kill_entity\",\"target\":\"minecraft:wither\",\"count\":2,\"description\":\"Kill\"},\n"
            + "    {\"id\":\"o\",\"type\":\"obtain_item\",\"target\":\"exampleguns:assault_rifle\",\"count\":1,\"required\":false},\n"
            + "    {\"id\":\"h\",\"type\":\"have_item\",\"target\":\"minecraft:diamond\",\"count\":3,\"track_before_unlock\":true},\n"
            + "    {\"id\":\"d\",\"type\":\"deliver_item\",\"target\":\"minecraft:coal\",\"count\":5},\n"
            + "    {\"id\":\"vd\",\"type\":\"visit_dimension\",\"target\":\"minecraft:the_nether\"},\n"
            + "    {\"id\":\"vl\",\"type\":\"visit_location\",\"target\":\"minecraft:overworld\",\"x\":10.5,\"y\":64,\"z\":-20,\"radius\":12},\n"
            + "    {\"id\":\"b\",\"type\":\"break_block\",\"target\":\"minecraft:stone\",\"count\":9},\n"
            + "    {\"id\":\"i\",\"type\":\"interact_block\",\"target\":\"minecraft:lever\",\"count\":1}\n"
            + "  ],\n"
            + "  \"rewards\": [\n"
            + "    {\"id\":\"r1\",\"type\":\"item\",\"item\":\"minecraft:diamond\",\"count\":3,\"nbt\":\"{Damage:0}\"},\n"
            + "    {\"id\":\"r2\",\"type\":\"experience\",\"amount\":5,\"unit\":\"levels\"},\n"
            + "    {\"id\":\"r3\",\"type\":\"command\",\"command\":\"give {player} minecraft:apple 1\",\"label\":\"An apple\"},\n"
            + "    {\"id\":\"r4\",\"type\":\"custom\",\"provider\":\"examplemod:suit\",\"data\":{\"level\":2}}\n"
            + "  ],\n"
            + "  \"lore\": [{\"title\":\"Page\",\"text\":\"Text\",\"icon\":\"minecraft:book\"}],\n"
            + "  \"dialogues\": [{\"id\":\"intro\",\"title\":\"Hi\",\"gate\":\"accept\",\"lines\":[\n"
            + "     {\"speaker\":\"Vale\",\"text\":\"Hello\",\"choices\":[{\"text\":\"Yes\",\"next\":1},{\"text\":\"No\",\"next\":-1}]},\n"
            + "     {\"speaker\":\"Vale\",\"text\":\"Great\"}]}],\n"
            + "  \"future_field\": {\"keep\": true}\n"
            + "}";

    @Test
    void parsesEveryFeatureAndRoundTripsLosslessly() {
        QuestCodec.ParseResult r = QuestCodec.parse(FULL, "test");
        assertTrue(r.ok(), r.issues().toString());
        QuestDefinition q = r.quest();
        assertEquals(QuestCategory.BOSS_QUESTS, q.category);
        assertEquals(8, q.objectives.size());
        assertEquals(ObjectiveType.VISIT_LOCATION, q.objectives.get(5).type);
        assertEquals(12, q.objectives.get(5).radius);
        assertFalse(q.objectives.get(1).required);
        assertTrue(q.objectives.get(2).trackBeforeUnlock);
        assertEquals(RewardType.CUSTOM, q.rewards.get(3).type);
        assertEquals(1, q.dialogues.get(0).lines.get(0).choices.get(0).next);

        String once = QuestCodec.toJson(q, true);
        QuestCodec.ParseResult again = QuestCodec.parse(once, "test");
        assertTrue(again.ok(), again.issues().toString());
        assertEquals(once, QuestCodec.toJson(again.quest(), true), "serialisation is stable");
        // unknown top-level fields survive a round trip through the editor model
        assertTrue(once.contains("future_field"));
        // and produced a warning, not an error
        boolean warned = false;
        for (Issue i : r.issues()) {
            warned |= i.code().equals("unknown_field");
        }
        assertTrue(warned);
    }

    @Test
    void clientJsonNeverContainsCommandsOrUnknownFields() {
        QuestDefinition q = QuestCodec.parse(FULL, "test").quest();
        String client = QuestCodec.clientJson(q);
        assertFalse(client.contains("give {player}"), "command text must not be sent to players");
        assertFalse(client.contains("future_field"));
        assertTrue(client.contains(QuestCodec.HIDDEN_COMMAND));
        assertSame(client, QuestCodec.clientJson(q), "cached");
        // the client form still parses with the same code path
        assertTrue(QuestCodec.parse(client, "client").ok());
    }

    @Test
    void rejectsBadIdsAndExplainsFourCategories() {
        QuestCodec.ParseResult r = QuestCodec.parse("{\"id\":\"Bad Id\",\"title\":\"T\",\"category\":\"endgame\"}", "f");
        assertFalse(r.ok());
        assertNull(r.quest());
        StringBuilder all = new StringBuilder();
        for (Issue i : r.issues()) {
            all.append(i.code()).append(' ').append(i.message()).append('\n');
        }
        assertTrue(all.toString().contains("bad_id"));
        assertTrue(all.toString().contains("exactly four categories"));
    }

    @Test
    void categoryAliasesAndRegistryIdNormalisation() {
        QuestCodec.ParseResult r = QuestCodec.parse("{\"id\":\"a:b\",\"title\":\"T\",\"category\":\"boss\",\"icon\":\"Diamond\","
                + "\"objectives\":[{\"id\":\"x\",\"type\":\"break_block\",\"target\":\"stone\",\"count\":1}]}", "f");
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(QuestCategory.BOSS_QUESTS, r.quest().category);
        assertEquals("minecraft:diamond", r.quest().icon);
        assertEquals("minecraft:stone", r.quest().objectives.get(0).target);
    }

    @Test
    void reportsPreciseJsonPaths() {
        QuestCodec.ParseResult r = QuestCodec.parse("{\"id\":\"a:b\",\"title\":\"T\",\"category\":\"main_story\",\"objectives\":["
                + "{\"id\":\"x\",\"type\":\"kill_entity\",\"target\":\"minecraft:zombie\",\"count\":0},"
                + "{\"id\":\"x\",\"type\":\"fly\"},"
                + "{\"id\":\"z\",\"type\":\"kill_entity\"}]}", "f");
        assertFalse(r.ok());
        boolean count = false, type = false, dup = false, target = false;
        for (Issue i : r.issues()) {
            count |= i.path().equals("objectives[0].count");
            type |= i.path().equals("objectives[1].type");
            dup |= i.code().equals("duplicate_id");
            target |= i.path().equals("objectives[2].target");
        }
        assertTrue(count && type && dup && target, r.issues().toString());
    }

    @Test
    void validatesDialogueChoiceTargetsAndRejectsUnsupportedFiles() {
        assertFalse(QuestCodec.parse("{\"id\":\"a:b\",\"title\":\"T\",\"category\":\"main\",\"dialogues\":[{\"id\":\"d\",\"lines\":["
                + "{\"text\":\"x\",\"choices\":[{\"text\":\"c\",\"next\":5}]}]}]}", "f").ok());
        assertFalse(QuestCodec.parse("{\"schema_version\":99,\"id\":\"a:b\",\"title\":\"T\",\"category\":\"main\"}", "f").ok());
        assertFalse(QuestCodec.parse("{\"quests\":[]}", "f").ok());
        assertFalse(QuestCodec.parse("[1,2]", "f").ok());
        assertFalse(QuestCodec.parse("{ not json", "f").ok());
    }

    @Test
    void idRules() {
        assertTrue(Ids.isQuestId("superhero:recover_the_artifact"));
        assertTrue(Ids.isQuestId("my_pack:chapter1/boss.fight-2"));
        assertFalse(Ids.isQuestId("NoNamespace"));
        assertFalse(Ids.isQuestId("ns:Upper"));
        assertFalse(Ids.isQuestId("ns:"));
        assertFalse(Ids.isQuestId("ns:../escape"));
        assertNotNull(Ids.normalizeResourceId("zombie"));
        assertNull(Ids.normalizeResourceId("bad id"));
        assertEquals("a__b_c", Ids.fileSafe("a:b/c"));
    }
}
