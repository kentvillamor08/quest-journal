package com.questjournal.core.pack;

/** Safety limits for pack imports. Configurable on the server; the defaults are generous but bounded. */
public record ImportLimits(long maxArchiveBytes, long maxTotalBytes, long maxEntryBytes, int maxEntries) {
    public static ImportLimits defaults() {
        return new ImportLimits(64L << 20, 256L << 20, 2L << 20, 50_000);
    }
}
