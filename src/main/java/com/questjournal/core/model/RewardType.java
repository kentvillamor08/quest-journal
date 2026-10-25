package com.questjournal.core.model;

public enum RewardType implements JsonEnum {
    ITEM("item", "Item"),
    EXPERIENCE("experience", "Experience"),
    COMMAND("command", "Server command"),
    /** Extension point: handled by a provider registered through QuestJournalApi (no hard dependency). */
    CUSTOM("custom", "Custom (provider)");

    private final String json;
    private final String label;

    RewardType(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
