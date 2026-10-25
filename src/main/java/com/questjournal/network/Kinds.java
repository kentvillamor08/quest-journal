package com.questjournal.network;

/** Message kinds carried by {@link BlobMsg}. Every payload is UTF-8 JSON. */
public final class Kinds {
    // server -> client
    public static final byte SYNC = 1;
    public static final byte DELTA = 2;
    public static final byte NOTIFY = 3;
    public static final byte OPEN_JOURNAL = 4;
    public static final byte EDITOR_DATA = 5;
    public static final byte RESULT = 6;
    // client -> server
    public static final byte ACTION = 20;
    public static final byte ADMIN = 21;

    private Kinds() {
    }
}
