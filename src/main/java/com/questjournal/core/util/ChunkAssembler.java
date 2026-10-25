package com.questjournal.core.util;

import java.io.ByteArrayOutputStream;

/**
 * Reassembles one chunked payload (chunks must arrive in order). Network packets are limited to ~32 KB from client
 * to server, so larger JSON payloads are split; this class enforces order and a hard size cap per transfer.
 */
public final class ChunkAssembler {
    private final int maxBytes;
    private int xfer = -1;
    private int nextSeq;
    private int total;
    private ByteArrayOutputStream buf;

    public ChunkAssembler(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Returns the complete payload after the last chunk, otherwise null. Throws on protocol violations. */
    public byte[] accept(int transferId, int seq, int totalChunks, byte[] data) {
        if (totalChunks < 1 || totalChunks > 1_000_000 || seq < 0 || seq >= totalChunks) {
            reset();
            throw new IllegalArgumentException("invalid chunk header");
        }
        if (seq == 0) {
            xfer = transferId;
            nextSeq = 0;
            total = totalChunks;
            buf = new ByteArrayOutputStream();
        } else if (buf == null || transferId != xfer || seq != nextSeq || totalChunks != total) {
            reset();
            throw new IllegalArgumentException("out-of-order chunk");
        }
        if (buf.size() + (long) data.length > maxBytes) {
            reset();
            throw new IllegalArgumentException("payload larger than " + maxBytes + " bytes");
        }
        buf.write(data, 0, data.length);
        nextSeq++;
        if (nextSeq == total) {
            byte[] out = buf.toByteArray();
            reset();
            return out;
        }
        return null;
    }

    private void reset() {
        buf = null;
        xfer = -1;
        nextSeq = 0;
    }
}
