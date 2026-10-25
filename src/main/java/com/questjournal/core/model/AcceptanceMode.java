package com.questjournal.core.model;

public enum AcceptanceMode implements JsonEnum {
    /** Starts by itself as soon as its prerequisites are met. */
    AUTO("auto", "Automatic start"),
    /** Appears as Available; objectives only progress after the player accepts. */
    MANUAL("manual", "Manual acceptance");

    private final String json;
    private final String label;

    AcceptanceMode(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
