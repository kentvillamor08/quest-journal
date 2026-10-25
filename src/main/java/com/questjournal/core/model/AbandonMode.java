package com.questjournal.core.model;

public enum AbandonMode implements JsonEnum {
    DISABLED("disabled", "Cannot abandon"),
    PRESERVE_PROGRESS("preserve_progress", "Keep progress"),
    RESET_PROGRESS("reset_progress", "Reset progress");

    private final String json;
    private final String label;

    AbandonMode(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
