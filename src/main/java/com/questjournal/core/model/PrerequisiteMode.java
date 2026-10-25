package com.questjournal.core.model;

public enum PrerequisiteMode implements JsonEnum {
    ALL("all", "All prerequisites"),
    ANY("any", "Any prerequisite");

    private final String json;
    private final String label;

    PrerequisiteMode(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
