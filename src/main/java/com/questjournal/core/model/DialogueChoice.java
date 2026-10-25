package com.questjournal.core.model;

/** A player choice at the end of a dialogue line. {@link #next} is a 0-based line index, or -1 to end the dialogue. */
public final class DialogueChoice {
    public String text = "";
    public int next = -1;

    public DialogueChoice copy() {
        DialogueChoice c = new DialogueChoice();
        c.text = text;
        c.next = next;
        return c;
    }
}
