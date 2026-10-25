package com.questjournal.core.model;

public enum ObjectiveType implements JsonEnum {
    KILL_ENTITY("kill_entity", "Kill entity", RegistryKind.ENTITY, true),
    OBTAIN_ITEM("obtain_item", "Obtain item", RegistryKind.ITEM, true),
    HAVE_ITEM("have_item", "Have item", RegistryKind.ITEM, true),
    DELIVER_ITEM("deliver_item", "Deliver item", RegistryKind.ITEM, true),
    VISIT_DIMENSION("visit_dimension", "Visit dimension", RegistryKind.DIMENSION, false),
    VISIT_LOCATION("visit_location", "Reach location", RegistryKind.DIMENSION, false),
    BREAK_BLOCK("break_block", "Break block", RegistryKind.BLOCK, true),
    INTERACT_BLOCK("interact_block", "Interact with block", RegistryKind.BLOCK, true);

    private final String json;
    private final String label;
    /** Which registry the objective "target" belongs to (for VISIT_LOCATION it is the dimension). */
    public final RegistryKind targetKind;
    public final boolean usesCount;

    ObjectiveType(String json, String label, RegistryKind targetKind, boolean usesCount) {
        this.json = json;
        this.label = label;
        this.targetKind = targetKind;
        this.usesCount = usesCount;
    }

    @Override public String json() { return json; }
    @Override public String label() { return label; }
}
