package com.questjournal.core.pack;

import java.nio.file.Path;
import java.util.List;

/** Metadata from a pack's pack.json. The pack id always equals its directory name. */
public record PackInfo(String id, String name, String version, String description, List<String> authors, Path dir,
                       boolean stale) {
    public PackInfo asStale() {
        return new PackInfo(id, name, version, description, authors, dir, true);
    }
}
