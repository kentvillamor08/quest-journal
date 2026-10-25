package com.questjournal.core.model;

public enum DialogueGate implements JsonEnum {
    NONE("none", "Optional"),
    ACCEPT("accept", "Required before accepting"),
    CLAIM("claim", "Required before claiming");

    private final String json;
    private final String label;

    DialogueGate(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
