package com.questjournal.core.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One objective of a quest. Identified by a stable id so saved progress survives edits and reordering. */
public final class ObjectiveDefinition {
    public String id = "";
    public ObjectiveType type = ObjectiveType.KILL_ENTITY;
    /** Registry id of the entity / item / block / dimension this objective is about. */
    public String target = "";
    public int count = 1;
    public String description = "";
    /** Optional objectives never block the quest from becoming claimable. */
    public boolean required = true;
    /** Sequential quests normally ignore events for objectives that are not revealed yet; set to count them anyway. */
    public boolean trackBeforeUnlock = false;
    // VISIT_LOCATION only
    public double x;
    public double y = 64;
    public double z;
    public int radius = 8;
    /** Unknown JSON fields, preserved so files survive a round trip through the in-game editor. */
    public Map<String, Object> extra = new LinkedHashMap<>();

    public ObjectiveDefinition copy() {
        ObjectiveDefinition o = new ObjectiveDefinition();
        o.id = id;
        o.type = type;
        o.target = target;
        o.count = count;
        o.description = description;
        o.required = required;
        o.trackBeforeUnlock = trackBeforeUnlock;
        o.x = x;
        o.y = y;
        o.z = z;
        o.radius = radius;
        o.extra = new LinkedHashMap<>(extra);
        return o;
    }

    /** Required progress; location/dimension objectives are all-or-nothing. */
    public int requiredCount() {
        return type.usesCount ? Math.max(1, count) : 1;
    }

    /** Plain-text fallback shown when the author did not write a description. */
    public String autoDescription() {
        switch (type) {
            case KILL_ENTITY: return "Defeat " + count + "x " + target;
            case OBTAIN_ITEM: return "Obtain " + count + "x " + target;
            case HAVE_ITEM: return "Have " + count + "x " + target + " in your inventory";
            case DELIVER_ITEM: return "Deliver " + count + "x " + target;
            case VISIT_DIMENSION: return "Travel to " + target;
            case VISIT_LOCATION:
                return String.format(Locale.ROOT, "Reach %.0f, %.0f, %.0f in %s (within %d blocks)", x, y, z, target, radius);
            case BREAK_BLOCK: return "Break " + count + "x " + target;
            case INTERACT_BLOCK: return "Interact with " + target + " (" + count + "x)";
            default: return id;
        }
    }
}
