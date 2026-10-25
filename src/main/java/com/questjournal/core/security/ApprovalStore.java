package com.questjournal.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;

/** Persistent set of operator-approved command rewards (config/questjournal/approved_commands.json). */
public final class ApprovalStore implements ApprovalChecker {
    private final Path file;
    private final Map<String, Object> approved = new LinkedHashMap<>();

    public ApprovalStore(Path file) {
        this.file = file;
    }

    /** Loads the file; a missing file means "nothing approved". Throws if the file exists but is unreadable. */
    public synchronized void load() throws IOException, JsonException {
        approved.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = Json.asObject(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        Map<String, Object> map = root == null ? null : Json.asObject(root.get("approved"));
        if (map != null) {
            approved.putAll(map);
        }
    }

    @Override
    public synchronized boolean isApproved(String fingerprint) {
        return approved.containsKey(fingerprint);
    }

    public synchronized void approve(String fingerprint, String questId, String rewardId, String approvedBy, long now)
            throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("quest", questId);
        entry.put("reward", rewardId);
        entry.put("approved_by", approvedBy);
        entry.put("approved_at", now);
        approved.put(fingerprint, entry);
        save();
    }

    public synchronized boolean revoke(String fingerprint) throws IOException {
        boolean removed = approved.remove(fingerprint) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized int size() {
        return approved.size();
    }

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1L);
        root.put("approved", approved);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root, true), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
