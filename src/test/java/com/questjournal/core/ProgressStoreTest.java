package com.questjournal.core;

import static com.questjournal.core.TestData.obj;
import static com.questjournal.core.TestData.quest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.state.PlayerProgress;
import com.questjournal.core.state.ProgressCodec;
import com.questjournal.core.state.ProgressStore;
import com.questjournal.core.state.QuestProgress;

class ProgressStoreTest {
    private static PlayerProgress sample(UUID id) {
        PlayerProgress p = new PlayerProgress(id);
        QuestProgress qp = new QuestProgress();
        qp.status = QuestStatus.CLAIMABLE;
        qp.since = 1234L;
        qp.objective("kill").count = 5;
        qp.objective("kill").done = true;
        qp.objective("kill").revealed = true;
        qp.grantedRewards.add("r1");
        qp.dialoguesDone.add("intro");
        p.quests.put("pack:quest_one", qp);
        p.extra.put("future", "keep me");
        return p;
    }

    @Test
    void roundTripKeepsEverythingIncludingUnknownFields() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProgress back = ProgressCodec.fromJson(ProgressCodec.toJson(sample(id)), id);
        QuestProgress qp = back.get("pack:quest_one");
        assertEquals(QuestStatus.CLAIMABLE, qp.status);
        assertEquals(5, qp.objectives.get("kill").count);
        assertTrue(qp.objectives.get("kill").done && qp.objectives.get("kill").revealed);
        assertTrue(qp.grantedRewards.contains("r1") && qp.dialoguesDone.contains("intro"));
        assertEquals("keep me", back.extra.get("future"));
    }

    @Test
    void storePersistsAcrossRestartsAndSkipsCleanFiles(@TempDir Path dir) {
        UUID id = UUID.randomUUID();
        List<String> errors = new ArrayList<>();
        ProgressStore store = new ProgressStore(dir.resolve("players"), errors::add);
        assertEquals(0, store.load(id).quests.size(), "unknown player starts empty");

        PlayerProgress p = sample(id);
        p.dirty = true;
        store.saveAsync(p);
        store.close(); // flushes the background writer, like a server stop
        assertTrue(Files.isRegularFile(store.fileFor(id)));
        assertFalse(p.dirty);

        ProgressStore again = new ProgressStore(dir.resolve("players"), errors::add);
        assertEquals(QuestStatus.CLAIMABLE, again.load(id).get("pack:quest_one").status, "survives a restart");
        again.close();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void corruptFileIsQuarantinedNotOverwritten(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        Path players = dir.resolve("players");
        Files.createDirectories(players);
        Files.writeString(players.resolve(id + ".json"), "{ this is not json", StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();
        ProgressStore store = new ProgressStore(players, errors::add);
        assertEquals(0, store.load(id).quests.size());
        assertEquals(1, errors.size(), "loud error in the log");
        try (Stream<Path> s = Files.list(players)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")), "original bytes are kept");
        }
        store.close();
    }

    @Test
    void saveNowWritesImmediatelyForRewardClaims(@TempDir Path dir) {
        UUID id = UUID.randomUUID();
        ProgressStore store = new ProgressStore(dir, s -> { });
        PlayerProgress p = sample(id);
        store.saveNow(p);
        assertTrue(Files.isRegularFile(store.fileFor(id)));
        store.close();
    }

    @Test
    void progressSurvivesQuestRenamesBecauseItIsKeyedByIds(@TempDir Path dir) throws Exception {
        QuestDefinition q = quest("pack:quest_one", AcceptanceMode.AUTO);
        obj(q, "kill", ObjectiveType.KILL_ENTITY, "minecraft:zombie", 5);
        UUID id = UUID.randomUUID();
        PlayerProgress back = ProgressCodec.fromJson(ProgressCodec.toJson(sample(id)), id);
        q.title = "Renamed";
        assertEquals(5, back.get(q.id).objectives.get(q.objectives.get(0).id).count);
    }
}
