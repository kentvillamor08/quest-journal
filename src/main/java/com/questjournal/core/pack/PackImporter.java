package com.questjournal.core.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.Severity;

/**
 * Imports a quest-pack ZIP from the designated import directory.
 * <ul>
 *   <li>The file name may not contain path separators; the resolved path must stay inside the import directory.</li>
 *   <li>Every archive entry path is validated (no "..", absolute paths, backslashes, drive letters, empty or
 *       excessively deep segments) BEFORE anything is written; an unsafe entry rejects the whole archive.</li>
 *   <li>Sizes are counted while streaming, so a lying zip header (zip bomb) cannot exceed the limits.</li>
 *   <li>Only pack.json, quests/**&#47;*.json and a few text files are extracted, into a private staging folder.</li>
 *   <li>The staged pack must parse completely before it is moved into the packs directory.</li>
 *   <li>Nothing in the archive is ever executed; command rewards stay disabled until an operator approves them.</li>
 * </ul>
 */
public final class PackImporter {
    private PackImporter() {
    }

    public record ImportResult(boolean ok, String packId, String message, List<String> warnings,
                               List<String> questsWithCommands, int questCount) {
        static ImportResult fail(String message) {
            return new ImportResult(false, "", message, new ArrayList<>(), new ArrayList<>(), 0);
        }
    }

    private static final class ImportException extends Exception {
        private static final long serialVersionUID = 1L;

        ImportException(String m) {
            super(m);
        }
    }

    public static ImportResult importZip(Path importDir, String fileName, Path packsDir, Path stagingRoot,
                                         Path backupsDir, ImportLimits lim, boolean overwrite) {
        String nameProblem = checkFileName(fileName);
        if (nameProblem != null) {
            return ImportResult.fail("Rejected file name: " + nameProblem);
        }
        Path root = importDir.toAbsolutePath().normalize();
        Path zip = root.resolve(fileName).normalize();
        if (!zip.startsWith(root)) {
            return ImportResult.fail("The file must be inside the import folder.");
        }
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            return ImportResult.fail("No such file in the import folder: " + fileName);
        }
        Path staging = null;
        try {
            if (Files.size(zip) > lim.maxArchiveBytes()) {
                return ImportResult.fail("The archive is larger than the limit of " + lim.maxArchiveBytes() + " bytes.");
            }
            Files.createDirectories(stagingRoot);
            staging = Files.createTempDirectory(stagingRoot, "import-");
            List<String> warnings = new ArrayList<>();
            extract(zip, staging, lim, warnings);

            PackLoader.PackOutcome outcome = PackLoader.loadPack(staging, null, new HashMap<>(), null, lim.maxEntryBytes());
            if (outcome.info == null || outcome.hasErrors()) {
                StringBuilder sb = new StringBuilder("The pack is not valid and was not imported:");
                int shown = 0;
                for (Issue i : outcome.issues) {
                    if (i.severity() == Severity.ERROR && shown++ < 5) {
                        sb.append("\n  - ").append(i.format());
                    }
                }
                return ImportResult.fail(sb.toString());
            }
            String packId = outcome.info.id();
            List<String> withCommands = new ArrayList<>();
            for (LoadedQuest q : outcome.quests) {
                if (q.def().hasCommandRewards()) {
                    withCommands.add(q.def().id);
                }
            }
            Files.createDirectories(packsDir);
            Path dest = packsDir.resolve(packId).normalize();
            if (!dest.startsWith(packsDir.toAbsolutePath().normalize()) && !dest.startsWith(packsDir.normalize())) {
                return ImportResult.fail("Pack id resolves outside the packs folder.");
            }
            if (Files.exists(dest)) {
                if (!overwrite) {
                    return ImportResult.fail("A pack with id '" + packId + "' is already installed. Use the overwrite option to replace it (a backup is kept).");
                }
                Files.createDirectories(backupsDir);
                Files.move(dest, backupsDir.resolve(packId + "-" + System.currentTimeMillis()));
            }
            try {
                Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, dest);
            }
            return new ImportResult(true, packId, "Imported pack '" + packId + "' with " + outcome.quests.size() + " quest(s).",
                    warnings, withCommands, outcome.quests.size());
        } catch (ImportException e) {
            return ImportResult.fail(e.getMessage());
        } catch (IOException e) {
            return ImportResult.fail("Import failed: " + e.getMessage());
        } finally {
            deleteRecursively(staging);
        }
    }

    static String checkFileName(String n) {
        if (n == null || n.isEmpty()) {
            return "empty name";
        }
        if (n.length() > 128) {
            return "name too long";
        }
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.contains("\0") || n.indexOf(':') >= 0) {
            return "path separators, '..' and ':' are not allowed";
        }
        if (!n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return "only .zip files can be imported";
        }
        return null;
    }

    /** Returns null for a safe archive entry name, otherwise the reason it is unsafe. */
    static String checkEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return "empty entry name";
        }
        if (name.length() > 200) {
            return "entry name too long";
        }
        if (name.contains("\\")) {
            return "backslash in path";
        }
        if (name.contains("\0")) {
            return "NUL character";
        }
        if (name.startsWith("/")) {
            return "absolute path";
        }
        String[] parts = name.split("/", -1);
        if (parts.length > 12) {
            return "path is nested too deeply";
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            boolean trailingSlash = i == parts.length - 1 && p.isEmpty() && name.endsWith("/");
            if (trailingSlash) {
                continue;
            }
            if (p.isEmpty()) {
                return "empty path segment";
            }
            if (p.equals("..")) {
                return "parent directory reference (..)";
            }
            if (p.equals(".")) {
                return "'.' path segment";
            }
            if (p.indexOf(':') >= 0) {
                return "':' in path (drive letter or data stream)";
            }
        }
        return null;
    }

    private static boolean isExtractable(String rel) {
        if (rel.equals("pack.json")) {
            return true;
        }
        if (rel.startsWith("quests/") && rel.endsWith(".json") && !rel.contains("/.")) {
            return true;
        }
        return rel.equals("README.md") || rel.equals("README.txt") || rel.equals("LICENSE") || rel.equals("LICENSE.txt")
                || rel.equals("CHANGELOG.md");
    }

    private static void extract(Path zip, Path staging, ImportLimits lim, List<String> warnings)
            throws IOException, ImportException {
        try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = new ArrayList<>();
            for (ZipEntry e : Collections.list(zf.entries())) {
                entries.add(e);
                if (entries.size() > lim.maxEntries()) {
                    throw new ImportException("The archive has more than " + lim.maxEntries() + " entries.");
                }
            }
            // 1) validate every path first; one unsafe entry rejects the whole archive
            for (ZipEntry e : entries) {
                String bad = checkEntryName(e.getName());
                if (bad != null) {
                    throw new ImportException("Unsafe path in archive: '" + e.getName() + "' (" + bad + "). Nothing was imported.");
                }
            }
            // 2) find the pack root (pack.json at top level or inside exactly one top-level folder)
            String prefix = null;
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (n.equals("pack.json")) {
                    prefix = "";
                    break;
                }
            }
            if (prefix == null) {
                List<String> candidates = new ArrayList<>();
                for (ZipEntry e : entries) {
                    String n = e.getName();
                    if (n.endsWith("/pack.json") && n.indexOf('/') == n.lastIndexOf('/')) {
                        candidates.add(n.substring(0, n.indexOf('/') + 1));
                    }
                }
                if (candidates.isEmpty()) {
                    throw new ImportException("No pack.json found at the top level of the archive.");
                }
                if (candidates.size() > 1) {
                    throw new ImportException("The archive contains more than one pack; import them one at a time.");
                }
                prefix = candidates.get(0);
            }
            // 3) extract allowed files with byte counting
            long total = 0;
            for (ZipEntry e : entries) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (!name.startsWith(prefix)) {
                    warnings.add("Skipped '" + name + "' (outside the pack folder).");
                    continue;
                }
                String rel = name.substring(prefix.length());
                if (!isExtractable(rel)) {
                    warnings.add("Skipped '" + name + "' (only pack.json, quests/**/*.json and README/LICENSE text files are imported).");
                    continue;
                }
                Path dest = staging.resolve(rel).normalize();
                if (!dest.startsWith(staging)) {
                    throw new ImportException("Unsafe path in archive: '" + name + "'.");
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zf.getInputStream(e); OutputStream out = Files.newOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    long entryBytes = 0;
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        entryBytes += r;
                        total += r;
                        if (entryBytes > lim.maxEntryBytes()) {
                            throw new ImportException("'" + name + "' is larger than the per-file limit of " + lim.maxEntryBytes() + " bytes.");
                        }
                        if (total > lim.maxTotalBytes()) {
                            throw new ImportException("The archive expands to more than " + lim.maxTotalBytes() + " bytes (possible zip bomb).");
                        }
                        out.write(buf, 0, r);
                    }
                }
            }
        } catch (ZipException e) {
            throw new ImportException("Not a valid zip archive: " + e.getMessage());
        }
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort cleanup of a temporary staging folder; leftovers are harmless and overwritten later.
                    p.toFile().deleteOnExit();
                }
            });
        } catch (IOException e) {
            dir.toFile().deleteOnExit();
        }
    }
}
