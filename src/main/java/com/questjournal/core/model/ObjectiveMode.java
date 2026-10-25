package com.questjournal.core.model;

public enum ObjectiveMode implements JsonEnum {
    ALL_AT_ONCE("all_at_once", "All at once"),
    SEQUENTIAL("sequential", "Sequential");

    private final String json;
    private final String label;

    ObjectiveMode(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
