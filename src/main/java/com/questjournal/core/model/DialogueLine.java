package com.questjournal.core.model;

import java.util.ArrayList;
import java.util.List;

/** A line of dialogue. Without choices the viewer simply continues with the next line. */
public final class DialogueLine {
    public String speaker = "";
    public String text = "";
    public List<DialogueChoice> choices = new ArrayList<>();

    public DialogueLine copy() {
        DialogueLine l = new DialogueLine();
        l.speaker = speaker;
        l.text = text;
        for (DialogueChoice c : choices) {
            l.choices.add(c.copy());
        }
        return l;
    }
}
