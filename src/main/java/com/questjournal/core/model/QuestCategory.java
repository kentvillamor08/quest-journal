package com.questjournal.core.model;

public enum QuestCategory implements JsonEnum {
    MAIN_STORY("main_story", "Main Story"),
    SIDE_QUESTS("side_quests", "Side Quests"),
    WORLD_QUESTS("world_quests", "World Quests"),
    BOSS_QUESTS("boss_quests", "Boss Quests");

    private final String json;
    private final String label;

    QuestCategory(String json, String label) {
        this.json = json;
        this.label = label;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
