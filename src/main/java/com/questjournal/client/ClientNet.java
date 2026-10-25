package com.questjournal.client;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.questjournal.QuestJournalMod;
import com.questjournal.client.editor.EditorScreen;
import com.questjournal.client.editor.EditorSession;
import com.questjournal.client.ui.JournalScreen;
import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.core.util.ChunkAssembler;
import com.questjournal.network.BlobMsg;
import com.questjournal.network.Kinds;
import com.questjournal.network.Net;

import net.minecraft.client.Minecraft;

/** Client end of the JSON transport. Runs on the render thread (enqueued by BlobMsg.handle). */
public final class ClientNet {
    private static final ChunkAssembler ASSEMBLER = new ChunkAssembler(64 << 20);

    private ClientNet() {
    }

    public static void receive(BlobMsg msg) {
        byte[] full;
        try {
            full = ASSEMBLER.accept(msg.transferId, msg.seq, msg.total, msg.data);
        } catch (IllegalArgumentException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Bad packet from the server: {}", e.getMessage());
            return;
        }
        if (full == null) {
            return;
        }
        Object json;
        try {
            json = Json.parse(new String(full, StandardCharsets.UTF_8));
        } catch (JsonException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Malformed JSON from the server: {}", e.getMessage());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Map<String, Object> m = Json.asObject(json);
        switch (msg.kind) {
            case Kinds.SYNC:
                if (m != null) {
                    ClientJournal.I.onSync(m);
                }
                break;
            case Kinds.DELTA:
                if (m != null) {
                    ClientJournal.I.onDelta(m);
                }
                break;
            case Kinds.NOTIFY:
                Notifications.show(Json.asArray(json));
                break;
            case Kinds.OPEN_JOURNAL:
                if (m != null && Boolean.TRUE.equals(m.get("editor"))) {
                    mc.setScreen(new EditorScreen(null));
                } else {
                    mc.setScreen(new JournalScreen(null));
                }
                break;
            case Kinds.EDITOR_DATA:
                if (m != null) {
                    EditorSession.onData(m);
                    if (mc.screen instanceof EditorScreen) {
                        ((EditorScreen) mc.screen).dataChanged();
                    }
                }
                break;
            case Kinds.RESULT:
                if (m != null) {
                    boolean ok = Boolean.TRUE.equals(m.get("ok"));
                    String text = String.valueOf(m.get("msg"));
                    ClientJournal.I.setResult(ok, text);
                    EditorSession.onResult(ok, text, String.valueOf(m.get("q")));
                    if (mc.screen instanceof EditorScreen) {
                        ((EditorScreen) mc.screen).dataChanged();
                    }
                }
                break;
            default:
                break;
        }
    }

    // ---- requests

    public static void action(String action, String questId, String objectiveId, String dialogueId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", action);
        m.put("q", questId);
        if (objectiveId != null) {
            m.put("o", objectiveId);
        }
        if (dialogueId != null) {
            m.put("d", dialogueId);
        }
        Net.toServer(Kinds.ACTION, Json.write(m, false));
    }

    public static void admin(Map<String, Object> m) {
        String json = Json.write(m, false);
        if (json.length() > 900_000) {
            ClientJournal.I.setResult(false, "That is too large to send (limit about 900 KB). Edit the JSON file on the server instead.");
            return;
        }
        Net.toServer(Kinds.ADMIN, json);
    }

    public static void adminOp(String op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        admin(m);
    }
}
