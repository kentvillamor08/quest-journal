package com.questjournal.core.model;

public enum XpUnit implements JsonEnum {
    POINTS("points", "Points"),
    LEVELS("levels", "Levels");

    private final String json;
    private final String label;

    XpUnit(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
