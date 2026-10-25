package com.questjournal.core.pack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.questjournal.core.util.Ids;

/** Writes pack.json and quests/**.json of one pack into a ZIP in the export folder. */
public final class PackExporter {
    private PackExporter() {
    }

    public static Path export(Path packsDir, String packId, Path exportDir) throws IOException {
        if (!Ids.isPackId(packId)) {
            throw new IOException("Invalid pack id '" + packId + "'");
        }
        Path root = packsDir.toAbsolutePath().normalize();
        Path dir = root.resolve(packId).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("No such pack: " + packId);
        }
        Files.createDirectories(exportDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = exportDir.resolve(packId + "-" + stamp + ".zip");

        List<Path> files = new ArrayList<>();
        Path manifest = dir.resolve("pack.json");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            files.add(manifest);
        }
        Path quests = dir.resolve("quests");
        if (Files.isDirectory(quests, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.walk(quests, 10)) {
                s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !dir.relativize(p).toString().replace('\\', '/').contains("/."))
                        .forEach(files::add);
            }
        }
        Collections.sort(files);
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Path f : files) {
                zos.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
        return zip;
    }
}
