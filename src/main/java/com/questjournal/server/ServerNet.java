package com.questjournal.server;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.questjournal.QuestJournalMod;
import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.network.BlobMsg;
import com.questjournal.network.Kinds;

import net.minecraft.server.level.ServerPlayer;

/** Entry point for every client-to-server message: reassemble, rate limit, parse defensively, dispatch. */
public final class ServerNet {
    private ServerNet() {
    }

    public static void receive(ServerPlayer player, BlobMsg msg) {
        QuestService svc = QuestService.get();
        PlayerData d = svc == null ? null : svc.data(player);
        if (d == null) {
            return;
        }
        byte[] full;
        try {
            full = d.assembler.accept(msg.transferId, msg.seq, msg.total, msg.data);
        } catch (IllegalArgumentException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Bad packet from {}: {}", player.getName().getString(), e.getMessage());
            return;
        }
        if (full == null || !svc.allow(d)) {
            return;
        }
        Map<String, Object> json;
        try {
            json = Json.asObject(Json.parse(new String(full, StandardCharsets.UTF_8)));
        } catch (JsonException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Malformed JSON from {}: {}", player.getName().getString(), e.getMessage());
            return;
        }
        if (json == null) {
            return;
        }
        if (msg.kind == Kinds.ACTION) {
            svc.handleAction(player, d, json);
        } else if (msg.kind == Kinds.ADMIN) {
            AdminService.handle(svc, player, json); // checks permission itself, for every single operation
        } else {
            QuestJournalMod.LOGGER.warn("[QuestJournal] {} sent an unexpected message kind {}", player.getName().getString(), msg.kind);
        }
    }
}
