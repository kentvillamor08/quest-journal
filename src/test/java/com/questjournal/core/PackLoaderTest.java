package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.pack.LoadResult;
import com.questjournal.core.pack.LoadedQuest;
import com.questjournal.core.pack.PackInfo;
import com.questjournal.core.pack.PackLoader;
import com.questjournal.core.pack.PackWriter;
import com.questjournal.core.validate.Issue;

class PackLoaderTest {
    static final long MAX = 1L << 20;

    static String quest(String id, String title) {
        return "{\"schema_version\":1,\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"category\":\"side_quests\"}";
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    static Path makePack(Path packs, String id) throws IOException {
        Path dir = packs.resolve(id);
        write(dir.resolve("pack.json"), "{\"schema_version\":1,\"id\":\"" + id + "\",\"name\":\"" + id + "\"}");
        return dir;
    }

    static LoadResult reload(Path packs, LoadResult prev) {
        Map<String, LoadedQuest> files = prev == null ? new HashMap<>() : prev.fileMap();
        Map<String, PackInfo> pk = prev == null ? new HashMap<>() : prev.packMap();
        return PackLoader.load(packs, files, pk, MAX);
    }

    @Test
    void loadsPacksAndQuestsFromNestedFolders(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/one.json"), quest("alpha:one", "One"));
        write(p.resolve("quests/deep/er/two.json"), quest("alpha:two", "Two"));
        write(p.resolve("quests/.trash/old.json.1"), "garbage");
        write(p.resolve("quests/.hidden/skipped.json"), quest("alpha:skipped", "Skipped"));
        LoadResult r = reload(packs, null);
        assertEquals(1, r.packs.size());
        assertEquals(2, r.quests.size());
        assertEquals(0, r.failedFiles);
        assertEquals("alpha", r.packOfQuest().get("alpha:one"));
    }

    @Test
    void brokenFileKeepsLastKnownGoodAndNothingOnDiskIsTouched(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/one.json"), quest("alpha:one", "One"));
        write(p.resolve("quests/two.json"), quest("alpha:two", "Two"));
        LoadResult first = reload(packs, null);

        String broken = "{ \"id\": \"alpha:two\", \"title\": ";
        write(p.resolve("quests/two.json"), broken);
        LoadResult second = reload(packs, first);
        assertEquals(2, second.quests.size(), "the quest stays live");
        assertEquals(1, second.failedFiles);
        boolean staleFlag = false;
        for (LoadedQuest q : second.quests) {
            staleFlag |= q.def().id.equals("alpha:two") && q.stale();
        }
        assertTrue(staleFlag);
        assertEquals(broken, Files.readString(p.resolve("quests/two.json")), "the admin's file is never rewritten");
        boolean told = false;
        for (Issue i : second.issues) {
            told |= i.code().equals("kept_previous_version");
        }
        assertTrue(told);

        write(p.resolve("quests/two.json"), quest("alpha:two", "Two (fixed)"));
        LoadResult third = reload(packs, second);
        assertEquals(0, third.failedFiles);
        for (LoadedQuest q : third.quests) {
            assertFalse(q.stale());
            if (q.def().id.equals("alpha:two")) {
                assertEquals("Two (fixed)", q.def().title);
            }
        }
    }

    @Test
    void firstLoadOfABrokenFileSkipsItWithoutBreakingTheRest(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/good.json"), quest("alpha:good", "Good"));
        write(p.resolve("quests/bad.json"), "{\"id\":\"Bad\"}");
        LoadResult r = reload(packs, null);
        assertEquals(1, r.quests.size());
        assertEquals(1, r.failedFiles);
    }

    @Test
    void deletedFilesDisappearOnReload(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/one.json"), quest("alpha:one", "One"));
        write(p.resolve("quests/two.json"), quest("alpha:two", "Two"));
        LoadResult first = reload(packs, null);
        Files.delete(p.resolve("quests/two.json"));
        assertEquals(1, reload(packs, first).quests.size());
    }

    @Test
    void brokenManifestKeepsTheWholePackFromBefore(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/one.json"), quest("alpha:one", "One"));
        LoadResult first = reload(packs, null);
        write(p.resolve("pack.json"), "{ nope");
        LoadResult second = reload(packs, first);
        assertEquals(1, second.packs.size());
        assertTrue(second.packs.get(0).stale());
        assertEquals(1, second.quests.size());
        assertTrue(second.quests.get(0).stale());
    }

    @Test
    void brokenManifestOnAFreshPackSkipsOnlyThatPack(@TempDir Path packs) throws Exception {
        Path good = makePack(packs, "good");
        write(good.resolve("quests/one.json"), quest("good:one", "One"));
        write(packs.resolve("broken/pack.json"), "{\"schema_version\":7,\"id\":\"broken\"}");
        write(packs.resolve("broken/quests/x.json"), quest("broken:x", "X"));
        LoadResult r = reload(packs, null);
        assertEquals(1, r.packs.size());
        assertEquals(1, r.quests.size());
    }

    @Test
    void duplicateQuestIdsAreReportedAndDeterministic(@TempDir Path packs) throws Exception {
        Path a = makePack(packs, "alpha");
        Path b = makePack(packs, "beta");
        write(a.resolve("quests/a.json"), quest("shared:id", "From alpha"));
        write(a.resolve("quests/b.json"), quest("shared:id", "Second in alpha"));
        write(b.resolve("quests/c.json"), quest("shared:id", "From beta"));
        LoadResult r = reload(packs, null);
        assertEquals(1, r.quests.size());
        assertEquals("From alpha", r.quests.get(0).def().title);
        assertEquals(2, r.failedFiles);
    }

    @Test
    void oversizedFilesAreRejectedUnread(@TempDir Path packs) throws Exception {
        Path p = makePack(packs, "alpha");
        write(p.resolve("quests/huge.json"), "{\"pad\":\"" + "x".repeat(2_000_000) + "\"}");
        write(p.resolve("quests/ok.json"), quest("alpha:ok", "Ok"));
        LoadResult r = reload(packs, null);
        assertEquals(1, r.quests.size());
        assertEquals(1, r.failedFiles);
    }

    @Test
    void packIdMustMatchTheFolderName(@TempDir Path packs) throws Exception {
        write(packs.resolve("folder/pack.json"), "{\"schema_version\":1,\"id\":\"other\"}");
        write(packs.resolve("folder/quests/x.json"), quest("other:x", "X"));
        assertEquals(0, reload(packs, null).quests.size());
    }

    @Test
    void editorWritesAreAtomicInsideThePackAndTrashIsRecoverable(@TempDir Path packs) throws Exception {
        QuestDefinition q = QuestCodec.parse(quest("srv:made_in_game", "Made in game"), "t").quest();
        assertNotNull(q);
        String rel = PackWriter.saveQuest(packs, "server_quests", null, q);
        assertEquals("quests/srv__made_in_game.json", rel);
        LoadResult r = reload(packs, null);
        assertEquals(1, r.quests.size());
        q.title = "Edited";
        assertEquals(rel, PackWriter.saveQuest(packs, "server_quests", rel, q));
        assertEquals("Edited", reload(packs, r).quests.get(0).def().title);

        PackWriter.trashQuest(packs, "server_quests", rel);
        assertEquals(0, reload(packs, null).quests.size());
        assertTrue(Files.isDirectory(packs.resolve("server_quests/.trash")));
    }

    @Test
    void pathsOutsideQuestsAreRefused() {
        assertEquals(null, PackWriter.safeRelPath("../evil.json"));
        assertEquals(null, PackWriter.safeRelPath("quests/../../evil.json"));
        assertEquals(null, PackWriter.safeRelPath("/etc/passwd.json"));
        assertEquals(null, PackWriter.safeRelPath("quests\\x.json"));
        assertEquals(null, PackWriter.safeRelPath("quests/x.txt"));
        assertEquals("quests/a/b.json", PackWriter.safeRelPath("quests/a/b.json"));
    }

    @Test
    void draftsAreStoredOutsideTheLivePacks(@TempDir Path root) throws Exception {
        Path drafts = root.resolve("drafts");
        PackWriter.saveDraft(drafts, "srv:wip", "{\"id\":\"srv:wip\"}");
        assertEquals(1, PackWriter.listDrafts(drafts, MAX).size());
        assertEquals(0, reload(root.resolve("packs"), null).quests.size());
        assertTrue(PackWriter.deleteDraft(drafts, "srv:wip"));
        assertEquals(0, PackWriter.listDrafts(drafts, MAX).size());
    }
}
