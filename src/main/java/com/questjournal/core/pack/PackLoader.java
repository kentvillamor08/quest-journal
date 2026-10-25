package com.questjournal.core.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.Severity;

/**
 * Reads quest packs from disk without touching any live state. Failure policy ("last known good"):
 * a quest file that cannot be loaded (bad JSON, schema error, unreadable) keeps its previously loaded version if
 * there is one, and a pack whose pack.json is broken keeps its previous version as a whole. Source files are
 * never modified, so nothing is ever lost on disk.
 */
public final class PackLoader {
    private static final int MAX_DEPTH = 10;

    private PackLoader() {
    }

    public static final class PackOutcome {
        public PackInfo info;
        public final List<LoadedQuest> quests = new ArrayList<>();
        public final List<Issue> issues = new ArrayList<>();
        public boolean manifestFailed;
        public int failedFiles;

        public boolean hasErrors() {
            for (Issue i : issues) {
                if (i.severity() == Severity.ERROR) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @param prevFiles    quests loaded by the previous successful pass, keyed by {@link LoadedQuest#fileKey()}
     * @param prevPacks    previous pack metadata keyed by pack id
     * @param maxFileBytes size cap per file (larger files are rejected, not read)
     */
    public static LoadResult load(Path packsDir, Map<String, LoadedQuest> prevFiles, Map<String, PackInfo> prevPacks,
                                  long maxFileBytes) {
        LoadResult res = new LoadResult();
        if (!Files.isDirectory(packsDir)) {
            res.issues.add(Issue.warn("no_packs_directory", "", "", "Packs directory does not exist: " + packsDir));
            return res;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(packsDir)) {
            for (Path d : ds) {
                if (Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) && !d.getFileName().toString().startsWith(".")) {
                    dirs.add(d);
                }
            }
        } catch (IOException e) {
            // The directory exists but cannot be listed: keep everything that was loaded before.
            res.issues.add(Issue.error("packs_unreadable", "", "", "Cannot list " + packsDir + ": " + e, false));
            res.packs.addAll(prevPacks.values());
            for (LoadedQuest q : prevFiles.values()) {
                res.quests.add(new LoadedQuest(q.def(), q.packId(), q.relPath(), true));
            }
            return res;
        }
        Collections.sort(dirs);

        Map<String, String> idToFile = new HashMap<>();
        for (Path dir : dirs) {
            String packId = dir.getFileName().toString();
            PackOutcome out = loadPack(dir, packId, prevFiles, prevPacks.get(packId), maxFileBytes);
            res.issues.addAll(out.issues);
            res.failedFiles += out.failedFiles;
            if (out.info == null) {
                continue;
            }
            res.packs.add(out.info);
            for (LoadedQuest lq : out.quests) {
                String other = idToFile.putIfAbsent(lq.def().id, lq.fileKey());
                if (other != null) {
                    res.failedFiles++;
                    res.issues.add(Issue.error("duplicate_quest_id", lq.def().id, "id",
                            "Quest id '" + lq.def().id + "' is already defined by " + other + "; this copy is ignored.", false)
                            .withFile(lq.fileKey()));
                } else {
                    res.quests.add(lq);
                }
            }
        }
        return res;
    }

    /**
     * Loads a single pack directory. {@code expectedId} is the directory name (or null when validating an import
     * staging folder, in which case the manifest's own id is used).
     */
    public static PackOutcome loadPack(Path dir, String expectedId, Map<String, LoadedQuest> prevFiles,
                                       PackInfo prevPack, long maxFileBytes) {
        PackOutcome out = new PackOutcome();
        String dirName = dir.getFileName().toString();
        String manifestFile = dirName + "/pack.json";
        PackManifest.Result mr = null;
        try {
            Path mf = dir.resolve("pack.json");
            if (!Files.isRegularFile(mf)) {
                throw new IOException("pack.json is missing");
            }
            if (Files.size(mf) > maxFileBytes) {
                throw new IOException("pack.json is larger than " + maxFileBytes + " bytes");
            }
            mr = PackManifest.parse(Files.readString(mf, StandardCharsets.UTF_8), dir, manifestFile);
            out.issues.addAll(mr.issues());
        } catch (IOException e) {
            out.issues.add(Issue.error("manifest_unreadable", "", "", "Cannot read " + manifestFile + ": " + e.getMessage(), false)
                    .withFile(manifestFile));
        }
        if (mr != null && mr.info() != null && expectedId != null && !mr.info().id().equals(expectedId)) {
            out.issues.add(Issue.error("pack_id_mismatch", "", "id", "pack.json id '" + mr.info().id()
                    + "' must match the folder name '" + expectedId + "'.", false).withFile(manifestFile));
            mr = null;
        }
        if (mr == null || mr.info() == null) {
            out.manifestFailed = true;
            if (prevPack != null) {
                out.info = prevPack.asStale();
                for (LoadedQuest q : prevFiles.values()) {
                    if (q.packId().equals(prevPack.id())) {
                        out.quests.add(new LoadedQuest(q.def(), q.packId(), q.relPath(), true));
                    }
                }
                out.issues.add(Issue.warn("kept_previous_pack", "", "", "Pack '" + prevPack.id()
                        + "' failed to load; its last known good version stays active."));
            } else {
                out.issues.add(Issue.warn("pack_skipped", "", "", "Pack folder '" + dirName + "' was skipped (see errors above)."));
            }
            return out;
        }
        out.info = mr.info();
        String packId = out.info.id();

        Path questsDir = dir.resolve("quests");
        if (!Files.isDirectory(questsDir)) {
            out.issues.add(Issue.warn("no_quests_folder", "", "", "Pack '" + packId + "' has no quests/ folder."));
            return out;
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(questsDir, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return !d.equals(questsDir) && d.getFileName().toString().startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    String n = f.getFileName().toString();
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink() && n.endsWith(".json") && !n.startsWith(".")) {
                        files.add(f);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException exc) {
                    out.issues.add(Issue.error("file_unreadable", "", "", "Cannot access " + f + ": " + exc.getMessage(), false));
                    out.failedFiles++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            out.issues.add(Issue.error("packs_unreadable", "", "", "Cannot scan " + questsDir + ": " + e, false));
        }
        Collections.sort(files);

        Map<String, String> idToFile = new HashMap<>();
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            String key = packId + "/" + rel;
            LoadedQuest prev = prevFiles.get(key);
            QuestCodec.ParseResult pr = null;
            String ioProblem = null;
            try {
                if (Files.size(f) > maxFileBytes) {
                    ioProblem = "file is larger than " + maxFileBytes + " bytes";
                } else {
                    pr = QuestCodec.parse(Files.readString(f, StandardCharsets.UTF_8), key);
                }
            } catch (IOException e) {
                ioProblem = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (ioProblem != null) {
                out.issues.add(Issue.error("file_unreadable", "", "", "Cannot read " + key + ": " + ioProblem, false).withFile(key));
            } else {
                out.issues.addAll(pr.issues());
            }
            QuestDefinition def = pr == null ? null : pr.quest();
            if (def == null) {
                out.failedFiles++;
                if (prev != null) {
                    out.quests.add(new LoadedQuest(prev.def(), packId, rel, true));
                    out.issues.add(Issue.warn("kept_previous_version", prev.def().id, "",
                            "File " + key + " failed to load; the previously loaded version of '" + prev.def().id + "' stays active.")
                            .withFile(key));
                }
                continue;
            }
            String other = idToFile.putIfAbsent(def.id, key);
            if (other != null) {
                out.failedFiles++;
                out.issues.add(Issue.error("duplicate_quest_id", def.id, "id", "Quest id '" + def.id
                        + "' is already defined by " + other + "; this file is ignored.", false).withFile(key));
                continue;
            }
            out.quests.add(new LoadedQuest(def, packId, rel, false));
        }
        return out;
    }
}
