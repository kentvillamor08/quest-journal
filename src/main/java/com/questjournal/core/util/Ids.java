package com.questjournal.core.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Syntax rules for every identifier used by quest packs. Pure string logic, no Minecraft classes. */
public final class Ids {
    private static final Pattern RESOURCE = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,100}$");
    private static final Pattern LOCAL = Pattern.compile("^[a-z0-9_]{1,64}$");
    public static final int MAX_QUEST_ID_LENGTH = 128;

    private Ids() {
    }

    /** Quest IDs and registry IDs share the namespace:path form (the same rules as ResourceLocation). */
    public static boolean isResourceId(String s) {
        if (s == null || !RESOURCE.matcher(s).matches()) {
            return false;
        }
        // The path part may contain '/' but no empty, "." or ".." segments (no traversal-looking ids).
        for (String seg : s.substring(s.indexOf(':') + 1).split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public static boolean isQuestId(String s) {
        return isResourceId(s) && s.length() <= MAX_QUEST_ID_LENGTH;
    }

    /** Objective, reward, dialogue and pack identifiers: lowercase letters, digits and underscore. */
    public static boolean isLocalId(String s) {
        return s != null && LOCAL.matcher(s).matches();
    }

    public static boolean isPackId(String s) {
        return isLocalId(s);
    }

    /** "stone" becomes "minecraft:stone"; returns null if the result is not a valid registry ID. */
    public static String normalizeResourceId(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        if (t.indexOf(':') < 0) {
            t = "minecraft:" + t;
        }
        return isResourceId(t) ? t : null;
    }

    public static String namespaceOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    /** File-system safe name for a quest, e.g. "superhero:recover_the_artifact" -> "superhero__recover_the_artifact". */
    public static String fileSafe(String questId) {
        return questId.replace(":", "__").replace('/', '_').replace('.', '_');
    }
}
