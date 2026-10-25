package com.questjournal.core.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.util.Ids;

/** Writes quests and drafts for the in-game editor. All writes are atomic and stay inside the packs directory. */
public final class PackWriter {
    private PackWriter() {
    }

    /** Creates {@code packs/<packId>/pack.json} if the pack does not exist yet. */
    public static Path ensurePack(Path packsDir, String packId, String name) throws IOException {
        if (!Ids.isPackId(packId)) {
            throw new IOException("Invalid pack id '" + packId + "'");
        }
        Path dir = packsDir.resolve(packId).normalize();
        if (!dir.startsWith(packsDir.normalize())) {
            throw new IOException("Pack path escapes the packs directory");
        }
        Files.createDirectories(dir.resolve("quests"));
        Path manifest = dir.resolve("pack.json");
        if (!Files.exists(manifest)) {
            atomicWrite(manifest, PackManifest.toJson(packId, name, "1.0.0", "Created by the in-game quest editor.", List.of()));
        }
        return dir;
    }

    /** Validates a relative path that must lie in quests/ and end with .json; returns the normalised path or null. */
    public static String safeRelPath(String rel) {
        if (rel == null || rel.isEmpty() || rel.length() > 200 || rel.contains("\\") || rel.contains("\0") || rel.startsWith("/")) {
            return null;
        }
        String[] parts = rel.split("/", -1);
        if (parts.length < 2 || parts.length > 11 || !parts[0].equals("quests")) {
            return null;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.indexOf(':') >= 0 || p.startsWith(".")) {
                return null;
            }
        }
        return rel.endsWith(".json") ? rel : null;
    }

    /** Writes the quest to its pack (existing file if known, otherwise a new file). Returns the relative path used. */
    public static String saveQuest(Path packsDir, String packId, String existingRelPath, QuestDefinition q) throws IOException {
        Path dir = ensurePack(packsDir, packId, packId);
        String rel = safeRelPath(existingRelPath);
        if (rel == null) {
            rel = "quests/" + Ids.fileSafe(q.id) + ".json";
        }
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(dir)) {
            throw new IOException("Quest path escapes its pack");
        }
        atomicWrite(target, QuestCodec.toJson(q, true));
        return rel;
    }

    /** Moves a quest file into the pack's .trash folder (recoverable). The loader ignores dot-folders. */
    public static void trashQuest(Path packsDir, String packId, String relPath) throws IOException {
        String rel = safeRelPath(relPath);
        if (rel == null || !Ids.isPackId(packId)) {
            throw new IOException("Invalid quest path");
        }
        Path dir = packsDir.resolve(packId).normalize();
        Path src = dir.resolve(rel).normalize();
        if (!src.startsWith(dir) || !Files.isRegularFile(src)) {
            throw new NoSuchFileException(rel);
        }
        Path trash = dir.resolve(".trash");
        Files.createDirectories(trash);
        Files.move(src, trash.resolve(src.getFileName() + "." + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ drafts (never loaded as live quests)

    public static void saveDraft(Path draftsDir, String questId, String json) throws IOException {
        if (!Ids.isQuestId(questId)) {
            throw new IOException("A draft needs a valid quest id (namespace:path)");
        }
        atomicWrite(draftsDir.resolve(Ids.fileSafe(questId) + ".json"), json);
    }

    public static boolean deleteDraft(Path draftsDir, String questId) throws IOException {
        return Ids.isQuestId(questId) && Files.deleteIfExists(draftsDir.resolve(Ids.fileSafe(questId) + ".json"));
    }

    /** Draft file name (without extension) to raw JSON text. */
    public static Map<String, String> listDrafts(Path draftsDir, long maxFileBytes) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(draftsDir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(draftsDir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".json") && Files.isRegularFile(p) && Files.size(p) <= maxFileBytes) {
                    out.put(n.substring(0, n.length() - 5), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
