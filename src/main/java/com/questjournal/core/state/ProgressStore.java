package com.questjournal.core.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.questjournal.core.json.JsonException;

/**
 * Per-player progress files: {@code <world>/questjournal/players/<uuid>.json}. Reads happen on login (tiny files);
 * routine saves are serialised on the caller's thread and written by one background thread so the server tick is
 * never blocked by disk I/O. Corrupt files are quarantined (renamed), never silently overwritten.
 */
public final class ProgressStore {
    private final Path dir;
    private final Consumer<String> errors;
    private final ExecutorService io;
    private volatile boolean closed;

    public ProgressStore(Path dir, Consumer<String> errors) {
        this.dir = dir;
        this.errors = errors;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "QuestJournal-IO");
            t.setDaemon(true);
            return t;
        });
    }

    public Path fileFor(UUID uuid) {
        return dir.resolve(uuid + ".json");
    }

    public PlayerProgress load(UUID uuid) {
        Path f = fileFor(uuid);
        if (!Files.isRegularFile(f)) {
            return new PlayerProgress(uuid);
        }
        try {
            return ProgressCodec.fromJson(Files.readString(f, StandardCharsets.UTF_8), uuid);
        } catch (IOException | JsonException e) {
            Path bad = f.resolveSibling(uuid + ".json.corrupt-" + System.currentTimeMillis());
            try {
                Files.move(f, bad, StandardCopyOption.REPLACE_EXISTING);
                errors.accept("Progress file for " + uuid + " is unreadable (" + e.getMessage()
                        + "). It was moved to " + bad.getFileName() + " and the player starts fresh.");
            } catch (IOException moveFailure) {
                errors.accept("Progress file for " + uuid + " is unreadable (" + e.getMessage()
                        + ") and could not be quarantined: " + moveFailure.getMessage());
            }
            return new PlayerProgress(uuid);
        }
    }

    /** Serialises now (cheap) and writes in the background. */
    public void saveAsync(PlayerProgress p) {
        if (!p.dirty) {
            return;
        }
        p.dirty = false;
        final String json = ProgressCodec.toJson(p);
        final Path target = fileFor(p.uuid);
        if (closed) {
            writeQuietly(target, json);
            return;
        }
        io.execute(() -> writeQuietly(target, json));
    }

    /** Synchronous save, used right after a reward claim so a crash cannot lose the "claimed" mark. */
    public void saveNow(PlayerProgress p) {
        p.dirty = false;
        writeQuietly(fileFor(p.uuid), ProgressCodec.toJson(p));
    }

    private void writeQuietly(Path target, String json) {
        try {
            write(target, json);
        } catch (IOException e) {
            errors.accept("Could not save " + target.getFileName() + ": " + e);
        }
    }

    static void write(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Waits for queued writes and stops the writer thread. Later saves are written synchronously. */
    public void close() {
        closed = true;
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                errors.accept("Timed out while flushing quest progress to disk.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
