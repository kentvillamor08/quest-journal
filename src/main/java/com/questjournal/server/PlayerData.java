package com.questjournal.server;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.questjournal.core.state.ActiveIndex;
import com.questjournal.core.state.Notice;
import com.questjournal.core.state.PlayerProgress;
import com.questjournal.core.util.ChunkAssembler;

/** Per-online-player runtime state. Holds the player's UUID only, never the entity, so nothing leaks across respawns. */
final class PlayerData {
    final UUID uuid;
    final PlayerProgress progress;
    ActiveIndex index = new ActiveIndex();
    /** Quest ids whose definitions the client already has. */
    final Set<String> clientKnown = new HashSet<>();
    /** Quest ids whose state changed since the last delta packet. */
    final Set<String> dirty = new LinkedHashSet<>();
    /** Coalesced notifications waiting for the next flush (later events replace earlier ones with the same key). */
    final Map<String, Notice> pending = new LinkedHashMap<>();
    final ChunkAssembler assembler = new ChunkAssembler(1 << 20);
    long rateWindowStart;
    int rateCount;
    long lastInteractTick = -1;
    long lastInteractPos;

    PlayerData(UUID uuid, PlayerProgress progress) {
        this.uuid = uuid;
        this.progress = progress;
    }
}
