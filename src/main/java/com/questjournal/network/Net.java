package com.questjournal.network;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.questjournal.QuestJournalMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Channel registration and chunked sending (both directions). */
public final class Net {
    public static final String PROTOCOL = "1";
    /** Payload bytes per packet; well under the 32767 byte client-to-server limit. */
    public static final int CHUNK_SIZE = 30_000;
    private static final AtomicInteger TRANSFER_IDS = new AtomicInteger();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QuestJournalMod.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static boolean registered;

    private Net() {
    }

    public static synchronized void init() {
        if (!registered) {
            registered = true;
            CHANNEL.registerMessage(0, BlobMsg.class, BlobMsg::encode, BlobMsg::decode, BlobMsg::handle);
        }
    }

    public static void toPlayer(ServerPlayer player, byte kind, String json) {
        send(kind, json, m -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), m));
    }

    public static void toServer(byte kind, String json) {
        send(kind, json, CHANNEL::sendToServer);
    }

    private static void send(byte kind, String json, java.util.function.Consumer<BlobMsg> sink) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int id = TRANSFER_IDS.incrementAndGet();
        int total = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(bytes.length, from + CHUNK_SIZE);
            sink.accept(new BlobMsg(kind, id, i, total, java.util.Arrays.copyOfRange(bytes, from, to)));
        }
    }
}
