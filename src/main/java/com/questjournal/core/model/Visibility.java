package com.questjournal.core.model;

public enum Visibility implements JsonEnum {
    ALWAYS("always", "Always visible"),
    WHEN_AVAILABLE("when_available", "Visible when available"),
    WHEN_ACTIVE("when_active", "Visible when started");

    private final String json;
    private final String label;

    Visibility(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
