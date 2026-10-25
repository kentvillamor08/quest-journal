package com.questjournal.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation shown inside the journal. The gate links it to quest progression: with gate ACCEPT the player
 * must finish reading it before the quest can be accepted, with CLAIM before the reward can be claimed.
 */
public final class DialogueSequence {
    public String id = "";
    public String title = "";
    public DialogueGate gate = DialogueGate.NONE;
    public List<DialogueLine> lines = new ArrayList<>();

    public DialogueSequence copy() {
        DialogueSequence d = new DialogueSequence();
        d.id = id;
        d.title = title;
        d.gate = gate;
        for (DialogueLine l : lines) {
            d.lines.add(l.copy());
        }
        return d;
    }
}
