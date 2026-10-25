package com.questjournal.network;

import java.util.function.Supplier;

import com.questjournal.QuestJournalMod;
import com.questjournal.client.ClientNet;
import com.questjournal.server.ServerNet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * The only packet of the mod: one chunk of a UTF-8 JSON payload. Splitting keeps every packet far below the
 * 32 KB client-to-server limit, and the receiver validates order and size (see ChunkAssembler).
 */
public final class BlobMsg {
    public final byte kind;
    public final int transferId;
    public final int seq;
    public final int total;
    public final byte[] data;

    public BlobMsg(byte kind, int transferId, int seq, int total, byte[] data) {
        this.kind = kind;
        this.transferId = transferId;
        this.seq = seq;
        this.total = total;
        this.data = data;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(kind);
        buf.writeVarInt(transferId);
        buf.writeVarInt(seq);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static BlobMsg decode(FriendlyByteBuf buf) {
        byte kind = buf.readByte();
        int id = buf.readVarInt();
        int seq = buf.readVarInt();
        int total = buf.readVarInt();
        byte[] data = buf.readByteArray(Net.CHUNK_SIZE + 64);
        return new BlobMsg(kind, id, seq, total, data);
    }

    public static void handle(BlobMsg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                if (ctx.getDirection().getReceptionSide().isServer()) {
                    ServerPlayer sender = ctx.getSender();
                    if (sender != null) {
                        ServerNet.receive(sender, msg);
                    }
                } else {
                    // Client-only code is referenced inside the lambda so a dedicated server never loads it.
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientNet.receive(msg));
                }
            } catch (RuntimeException e) {
                QuestJournalMod.LOGGER.warn("[QuestJournal] Dropped an invalid packet: {}", e.toString());
            }
        });
        ctx.setPacketHandled(true);
    }
}
