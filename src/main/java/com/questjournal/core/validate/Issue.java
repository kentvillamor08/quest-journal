package com.questjournal.core.validate;

/**
 * One validation finding.
 *
 * @param blocking if true the quest is shown to players as "needs attention" and cannot progress/claim
 *                 (unresolved required content, broken prerequisites, cycles). Non-blocking issues are advisory.
 */
public record Issue(Severity severity, String code, String questId, String file, String path, String message,
                    boolean blocking) {

    public static Issue error(String code, String questId, String path, String message, boolean blocking) {
        return new Issue(Severity.ERROR, code, questId == null ? "" : questId, "", path == null ? "" : path, message, blocking);
    }

    public static Issue warn(String code, String questId, String path, String message) {
        return new Issue(Severity.WARNING, code, questId == null ? "" : questId, "", path == null ? "" : path, message, false);
    }

    public static Issue info(String code, String questId, String path, String message) {
        return new Issue(Severity.INFO, code, questId == null ? "" : questId, "", path == null ? "" : path, message, false);
    }

    public Issue withFile(String f) {
        return new Issue(severity, code, questId, f, path, message, blocking);
    }

    /** Human readable one-liner for logs and chat. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(severity).append("] ");
        if (!questId.isEmpty()) {
            sb.append(questId);
        } else if (!file.isEmpty()) {
            sb.append(file);
        }
        if (!path.isEmpty()) {
            sb.append(' ').append(path);
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}
