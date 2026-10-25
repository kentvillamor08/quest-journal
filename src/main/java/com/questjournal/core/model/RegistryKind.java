package com.questjournal.core.model;

public enum RegistryKind {
    ITEM("Item"), ENTITY("Entity"), BLOCK("Block"), DIMENSION("Dimension");

    public final String label;

    RegistryKind(String label) {
        this.label = label;
    }
}
