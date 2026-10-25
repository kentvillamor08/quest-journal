package com.questjournal.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.questjournal.core.util.Ids;

/**
 * Security rules for command rewards. A command is only ever executed when ALL of these hold
 * (enforced by the server layer): the feature is enabled in the server config, the command passes
 * {@link #check}, an operator approved its fingerprint, and substitution of placeholders succeeded.
 */
public final class CommandPolicy {
    /** Roots that can never be used in rewards, even if someone puts them in the allowlist. */
    public static final Set<String> ALWAYS_DENIED = Set.of(
            "op", "deop", "stop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
            "save-all", "save-on", "save-off", "reload", "datapack", "function", "forceload",
            "execute", "schedule", "data", "setidletimeout", "publish", "debug", "perf", "jfr",
            "defaultgamemode", "difficulty", "gamerule", "setworldspawn", "worldborder", "questjournal");

    /** The only placeholders that may appear in a command: {player}, {uuid}, {quest}. */
    public static final Set<String> PLACEHOLDERS = Set.of("player", "uuid", "quest");
    public static final int MAX_LENGTH = 256;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_]+)\\}");
    private static final Pattern SELECTOR = Pattern.compile("@[aeprn](?![A-Za-z0-9_])");
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final boolean enabled;
    private final Set<String> allowedRoots = new HashSet<>();
    private final boolean allowSelectors;

    public CommandPolicy(boolean enabled, Collection<String> allowedRoots, boolean allowSelectors) {
        this.enabled = enabled;
        for (String r : allowedRoots) {
            this.allowedRoots.add(r.trim().toLowerCase(Locale.ROOT));
        }
        this.allowSelectors = allowSelectors;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Lower-cased first word without a leading slash and without the "minecraft:" prefix. */
    public static String root(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1).trim();
        }
        int sp = c.indexOf(' ');
        String r = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (r.startsWith("minecraft:")) {
            r = r.substring("minecraft:".length());
        }
        return r;
    }

    /** Returns the list of reasons the command is not acceptable; empty means it passes the policy. */
    public List<String> check(String command) {
        List<String> reasons = new ArrayList<>();
        if (command == null || command.trim().isEmpty()) {
            reasons.add("the command is empty");
            return reasons;
        }
        if (command.length() > MAX_LENGTH) {
            reasons.add("the command is longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                reasons.add("the command contains control characters or line breaks");
                break;
            }
        }
        String root = root(command);
        if (root.isEmpty()) {
            reasons.add("the command has no command word");
        } else if (root.indexOf(':') >= 0) {
            reasons.add("namespaced command roots (" + root + ") are not allowed");
        } else if (ALWAYS_DENIED.contains(root)) {
            reasons.add("'" + root + "' is never allowed in quest rewards");
        } else if (!allowedRoots.contains(root)) {
            reasons.add("'" + root + "' is not in the command allowlist (commandRewards.allowedRoots)");
        }
        if (!allowSelectors && SELECTOR.matcher(command).find()) {
            reasons.add("target selectors other than @s are disabled (commandRewards.allowSelectors)");
        }
        for (String name : placeholdersIn(command)) {
            if (!PLACEHOLDERS.contains(name)) {
                reasons.add("unknown placeholder {" + name + "} (supported: {player}, {uuid}, {quest})");
            }
        }
        return reasons;
    }

    public static List<String> placeholdersIn(String command) {
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(command == null ? "" : command);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Replaces the supported placeholders. Every substituted value is validated first, so player-influenced text can
     * never smuggle extra arguments or a second command into the privileged command line.
     */
    public String substitute(String command, String playerName, String playerUuid, String questId) {
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(command);
        int last = 0;
        while (m.find()) {
            out.append(command, last, m.start());
            String key = m.group(1);
            switch (key) {
                case "player":
                    require(playerName != null && PLAYER_NAME.matcher(playerName).matches(), "player name");
                    out.append(playerName);
                    break;
                case "uuid":
                    require(playerUuid != null && UUID_TEXT.matcher(playerUuid).matches(), "player UUID");
                    out.append(playerUuid);
                    break;
                case "quest":
                    require(Ids.isQuestId(questId), "quest id");
                    out.append(questId);
                    break;
                default:
                    throw new IllegalArgumentException("unknown placeholder {" + key + "}");
            }
            last = m.end();
        }
        out.append(command, last, command.length());
        return out.toString();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("refusing to substitute an unsafe " + what);
        }
    }

    /** Stable identity of one command reward; approvals are stored by fingerprint so editing the command revokes approval. */
    public static String fingerprint(String questId, String rewardId, String command) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((questId + "\n" + rewardId + "\n" + command.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
