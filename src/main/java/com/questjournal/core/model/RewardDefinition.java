package com.questjournal.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** One reward of a quest. Which fields matter depends on {@link #type}. */
public final class RewardDefinition {
    public String id = "";
    public RewardType type = RewardType.ITEM;
    // ITEM
    public String item = "";
    public int count = 1;
    /** Optional SNBT tag for the stack, e.g. {Damage:0}. Validated on the server. */
    public String nbt = "";
    // EXPERIENCE
    public int amount = 0;
    public XpUnit unit = XpUnit.POINTS;
    // COMMAND (runs on the server, only if command rewards are enabled AND this command was approved)
    public String command = "";
    /** Player-facing text for COMMAND/CUSTOM rewards. Players never see the command itself. */
    public String label = "";
    // CUSTOM
    public String provider = "";
    public Map<String, Object> data = new LinkedHashMap<>();
    public Map<String, Object> extra = new LinkedHashMap<>();

    public RewardDefinition copy() {
        RewardDefinition r = new RewardDefinition();
        r.id = id;
        r.type = type;
        r.item = item;
        r.count = count;
        r.nbt = nbt;
        r.amount = amount;
        r.unit = unit;
        r.command = command;
        r.label = label;
        r.provider = provider;
        r.data = new LinkedHashMap<>(data);
        r.extra = new LinkedHashMap<>(extra);
        return r;
    }
}
