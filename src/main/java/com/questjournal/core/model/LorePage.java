package com.questjournal.core.model;

/** One page of lore shown in the journal's lore viewer. */
public final class LorePage {
    public String title = "";
    public String text = "";
    /** Optional item id drawn as a small decoration next to the title. */
    public String icon = "";

    public LorePage copy() {
        LorePage p = new LorePage();
        p.title = title;
        p.text = text;
        p.icon = icon;
        return p;
    }
}
