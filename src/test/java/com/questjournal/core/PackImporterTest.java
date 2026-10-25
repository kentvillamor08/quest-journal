package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.questjournal.core.pack.ImportLimits;
import com.questjournal.core.pack.PackExporter;
import com.questjournal.core.pack.PackImporter;
import com.questjournal.core.pack.PackImporter.ImportResult;

class PackImporterTest {
    static final String MANIFEST = "{\"schema_version\":1,\"id\":\"imported\",\"name\":\"Imported\"}";
    static final String QUEST = "{\"schema_version\":1,\"id\":\"imported:one\",\"title\":\"One\",\"category\":\"main_story\"}";
    static final String CMD_QUEST = "{\"schema_version\":1,\"id\":\"imported:cmd\",\"title\":\"Cmd\",\"category\":\"main_story\","
            + "\"rewards\":[{\"id\":\"r1\",\"type\":\"command\",\"command\":\"give {player} minecraft:apple 1\"}]}";

    private static Path zip(Path dir, String name, String... namesAndContents) throws IOException {
        Files.createDirectories(dir);
        Path z = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(z); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zos.putNextEntry(new ZipEntry(namesAndContents[i]));
                zos.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return z;
    }

    private static ImportResult run(Path root, String file, boolean overwrite, ImportLimits lim) {
        return PackImporter.importZip(root.resolve("import"), file, root.resolve("packs"), root.resolve("staging"),
                root.resolve("backups"), lim, overwrite);
    }

    private static boolean nothingInstalled(Path root) throws IOException {
        Path packs = root.resolve("packs");
        if (!Files.exists(packs)) {
            return true;
        }
        try (Stream<Path> s = Files.list(packs)) {
            return s.findAny().isEmpty();
        }
    }

    @Test
    void importsAPackWithATopLevelFolderAndFlagsCommandRewards(@TempDir Path root) throws Exception {
        zip(root.resolve("import"), "good.zip", "mypack/pack.json", MANIFEST, "mypack/quests/one.json", QUEST,
                "mypack/quests/cmd.json", CMD_QUEST, "mypack/evil.exe", "MZ", "mypack/README.md", "hello");
        ImportResult r = run(root, "good.zip", false, ImportLimits.defaults());
        assertTrue(r.ok(), r.message());
        assertEquals("imported", r.packId());
        assertEquals(2, r.questCount());
        assertEquals(java.util.List.of("imported:cmd"), r.questsWithCommands(), "operators are told which quests run commands");
        assertTrue(Files.isRegularFile(root.resolve("packs/imported/quests/one.json")));
        assertTrue(Files.isRegularFile(root.resolve("packs/imported/README.md")));
        assertFalse(Files.exists(root.resolve("packs/imported/evil.exe")), "only pack data is extracted");
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void importsAPackWithFilesAtTheRoot(@TempDir Path root) throws Exception {
        zip(root.resolve("import"), "flat.zip", "pack.json", MANIFEST, "quests/one.json", QUEST);
        assertTrue(run(root, "flat.zip", false, ImportLimits.defaults()).ok());
    }

    @Test
    void anyUnsafeEntryPathRejectsTheWholeArchiveBeforeWritingAnything(@TempDir Path root) throws Exception {
        String[] evil = {"../evil.json", "/abs/evil.json", "quests/../../evil.json", "a\\..\\b.json",
                "C:/Windows/x.json", "quests//double.json", "pack.json/../../../x", "quests/./x.json"};
        for (int i = 0; i < evil.length; i++) {
            String name = "evil" + i + ".zip";
            zip(root.resolve("import"), name, "pack.json", MANIFEST, "quests/one.json", QUEST, evil[i], "{}");
            ImportResult r = run(root, name, false, ImportLimits.defaults());
            assertFalse(r.ok(), "must reject '" + evil[i] + "'");
            assertTrue(r.message().contains("Unsafe path"), r.message());
            assertTrue(nothingInstalled(root), "nothing may be installed for '" + evil[i] + "'");
        }
        assertFalse(Files.exists(root.resolve("evil.json")));
    }

    @Test
    void rejectsBadFileNamesAndFilesOutsideTheImportFolder(@TempDir Path root) throws Exception {
        zip(root, "outside.zip", "pack.json", MANIFEST);
        for (String n : new String[]{"../outside.zip", "..\\outside.zip", "/etc/x.zip", "sub/x.zip", "C:x.zip", "notazip.txt", ""}) {
            assertFalse(run(root, n, false, ImportLimits.defaults()).ok(), "must reject '" + n + "'");
        }
        assertFalse(run(root, "missing.zip", false, ImportLimits.defaults()).ok());
    }

    @Test
    void rejectsMalformedArchivesAndInvalidPacks(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("import"));
        Files.write(root.resolve("import/garbage.zip"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertFalse(run(root, "garbage.zip", false, ImportLimits.defaults()).ok());
        zip(root.resolve("import"), "nomanifest.zip", "quests/one.json", QUEST);
        assertFalse(run(root, "nomanifest.zip", false, ImportLimits.defaults()).ok());
        zip(root.resolve("import"), "badjson.zip", "pack.json", MANIFEST, "quests/one.json", "{ not json");
        ImportResult bad = run(root, "badjson.zip", false, ImportLimits.defaults());
        assertFalse(bad.ok());
        assertTrue(bad.message().contains("not imported"));
        zip(root.resolve("import"), "two.zip", "a/pack.json", MANIFEST, "b/pack.json", MANIFEST);
        assertFalse(run(root, "two.zip", false, ImportLimits.defaults()).ok());
        assertTrue(nothingInstalled(root));
    }

    @Test
    void sizeLimitsStopZipBombsEvenWhenHeadersLie(@TempDir Path root) throws Exception {
        String big = "{\"schema_version\":1,\"id\":\"imported:big\",\"title\":\"x\",\"category\":\"main_story\",\"description\":\""
                + "a".repeat(900_000) + "\"}";
        zip(root.resolve("import"), "big.zip", "pack.json", MANIFEST, "quests/big.json", big);
        ImportLimits tiny = new ImportLimits(1 << 20, 1 << 20, 100_000, 100);
        ImportResult r = run(root, "big.zip", false, tiny);
        assertFalse(r.ok());
        assertTrue(r.message().contains("per-file limit"), r.message());

        ImportLimits smallTotal = new ImportLimits(1 << 20, 500, 1 << 20, 100);
        assertFalse(run(root, "big.zip", false, smallTotal).ok());
        ImportLimits fewEntries = new ImportLimits(1 << 20, 1 << 20, 1 << 20, 1);
        zip(root.resolve("import"), "many.zip", "pack.json", MANIFEST, "quests/one.json", QUEST);
        assertFalse(run(root, "many.zip", false, fewEntries).ok());
        assertTrue(nothingInstalled(root));
    }

    @Test
    void existingPacksAreOnlyReplacedOnRequestAndAreBackedUp(@TempDir Path root) throws Exception {
        zip(root.resolve("import"), "p.zip", "pack.json", MANIFEST, "quests/one.json", QUEST);
        assertTrue(run(root, "p.zip", false, ImportLimits.defaults()).ok());
        ImportResult again = run(root, "p.zip", false, ImportLimits.defaults());
        assertFalse(again.ok());
        assertTrue(again.message().contains("already installed"));
        assertTrue(run(root, "p.zip", true, ImportLimits.defaults()).ok());
        try (Stream<Path> s = Files.list(root.resolve("backups"))) {
            assertEquals(1, s.count());
        }
        try (Stream<Path> s = Files.list(root.resolve("staging"))) {
            assertEquals(0, s.count(), "staging folders are cleaned up");
        }
    }

    @Test
    void exportProducesAZipThatImportsBackUnchanged(@TempDir Path root) throws Exception {
        zip(root.resolve("import"), "p.zip", "pack.json", MANIFEST, "quests/sub/one.json", QUEST);
        assertTrue(run(root, "p.zip", false, ImportLimits.defaults()).ok());
        Path exported = PackExporter.export(root.resolve("packs"), "imported", root.resolve("export"));
        assertTrue(Files.size(exported) > 0);

        Path other = root.resolve("second");
        Files.createDirectories(other.resolve("import"));
        Files.copy(exported, other.resolve("import/again.zip"));
        ImportResult r = PackImporter.importZip(other.resolve("import"), "again.zip", other.resolve("packs"),
                other.resolve("staging"), other.resolve("backups"), ImportLimits.defaults(), false);
        assertTrue(r.ok(), r.message());
        assertEquals(QUEST, Files.readString(other.resolve("packs/imported/quests/sub/one.json")));
    }

    @Test
    void exportRejectsPathTraversalInPackIds(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("packs"));
        for (String id : new String[]{"../x", "a/b", "", "..", "UPPER"}) {
            final String bad = id;
            try {
                PackExporter.export(root.resolve("packs"), bad, root.resolve("export"));
                throw new AssertionError("must reject '" + bad + "'");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }
}
