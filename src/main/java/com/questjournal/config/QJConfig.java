package com.questjournal.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;

import com.questjournal.core.security.CommandPolicy;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge configuration. CLIENT = appearance and notifications (config/questjournal-client.toml),
 * COMMON = server behaviour and security (config/questjournal-common.toml). Quest content never lives here.
 */
public final class QJConfig {
    private static final Predicate<Object> HEX = o -> o instanceof String && ((String) o).matches("^#[0-9A-Fa-f]{6}$");

    public static final Client CLIENT;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;

    static {
        Pair<Client, ForgeConfigSpec> c = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = c.getLeft();
        CLIENT_SPEC = c.getRight();
        Pair<Common, ForgeConfigSpec> s = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = s.getLeft();
        COMMON_SPEC = s.getRight();
    }

    private QJConfig() {
    }

    /** "#RRGGBB" to opaque ARGB; falls back to magenta so a typo is visible instead of crashing. */
    public static int argb(String hex) {
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (RuntimeException e) {
            return 0xFFFF00FF;
        }
    }

    public static CommandPolicy commandPolicy() {
        List<String> roots = new ArrayList<>();
        for (Object o : COMMON.allowedRoots.get()) {
            roots.add(String.valueOf(o));
        }
        return new CommandPolicy(COMMON.commandRewardsEnabled.get(), roots, COMMON.allowSelectors.get());
    }

    public static final class Client {
        public final ForgeConfigSpec.ConfigValue<String> backgroundColor;
        public final ForgeConfigSpec.ConfigValue<String> panelColor;
        public final ForgeConfigSpec.ConfigValue<String> accentColor;
        public final ForgeConfigSpec.ConfigValue<String> goldColor;
        public final ForgeConfigSpec.ConfigValue<String> textColor;
        public final ForgeConfigSpec.DoubleValue backgroundOpacity;
        public final ForgeConfigSpec.BooleanValue smoothScrolling;
        public final ForgeConfigSpec.BooleanValue notificationsEnabled;
        public final ForgeConfigSpec.IntValue notificationSeconds;
        public final ForgeConfigSpec.BooleanValue notifyAvailable;
        public final ForgeConfigSpec.BooleanValue notifyStarted;
        public final ForgeConfigSpec.BooleanValue notifyProgress;
        public final ForgeConfigSpec.BooleanValue notifyClaimable;
        public final ForgeConfigSpec.BooleanValue notifyClaimed;

        Client(ForgeConfigSpec.Builder b) {
            b.comment("Journal appearance. Colors are #RRGGBB.").push("appearance");
            backgroundColor = b.comment("Screen background").define("backgroundColor", "#0A0D13", HEX);
            panelColor = b.comment("Panels and lists").define("panelColor", "#141A24", HEX);
            accentColor = b.comment("Muted red accent").define("accentColor", "#A8323A", HEX);
            goldColor = b.comment("Gold accent").define("goldColor", "#C9A24B", HEX);
            textColor = b.comment("Main text").define("textColor", "#E3E6EC", HEX);
            backgroundOpacity = b.comment("1.0 = solid. Lower values show the game behind the journal (slightly more GPU work).")
                    .defineInRange("backgroundOpacity", 0.96, 0.3, 1.0);
            smoothScrolling = b.comment("Animate scrolling").define("smoothScrolling", true);
            b.pop();
            b.comment("Small temporary notifications (no permanent HUD tracker exists).").push("notifications");
            notificationsEnabled = b.define("enabled", true);
            notificationSeconds = b.comment("How long a notification stays").defineInRange("durationSeconds", 4, 1, 15);
            notifyAvailable = b.define("newQuestAvailable", true);
            notifyStarted = b.define("questStarted", true);
            notifyProgress = b.define("objectiveProgress", true);
            notifyClaimable = b.define("readyToClaim", true);
            notifyClaimed = b.define("rewardClaimed", true);
            b.pop();
        }
    }

    public static final class Common {
        public final ForgeConfigSpec.IntValue editorPermissionLevel;
        public final ForgeConfigSpec.BooleanValue singleplayerOwnerIsAdmin;
        public final ForgeConfigSpec.BooleanValue commandRewardsEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> allowedRoots;
        public final ForgeConfigSpec.BooleanValue allowSelectors;
        public final ForgeConfigSpec.IntValue commandPermissionLevel;
        public final ForgeConfigSpec.BooleanValue ignoreCreativePlayers;
        public final ForgeConfigSpec.IntValue haveItemFallbackTicks;
        public final ForgeConfigSpec.IntValue locationCheckTicks;
        public final ForgeConfigSpec.BooleanValue sendNotifications;
        public final ForgeConfigSpec.IntValue maxActionsPerSecond;
        public final ForgeConfigSpec.BooleanValue installExamplePack;
        public final ForgeConfigSpec.IntValue maxQuestFileKb;
        public final ForgeConfigSpec.IntValue importMaxArchiveMb;
        public final ForgeConfigSpec.IntValue importMaxTotalMb;
        public final ForgeConfigSpec.IntValue importMaxEntries;

        Common(ForgeConfigSpec.Builder b) {
            b.comment("Who may use the editor, reload, import/export and approvals.").push("permissions");
            editorPermissionLevel = b.comment("Minimum operator level (0-4)").defineInRange("editorPermissionLevel", 2, 0, 4);
            singleplayerOwnerIsAdmin = b.comment("In a single-player world the owner is an administrator even without cheats")
                    .define("singleplayerOwnerIsAdmin", true);
            b.pop();

            b.comment("COMMAND REWARDS RUN COMMANDS ON YOUR SERVER. They are disabled until you turn them on, and even then each",
                    "command must (1) have an allowlisted root, (2) pass the safety checks and (3) be approved by an operator",
                    "(/questjournal approve <quest_id>). Never enable this for packs you did not read.").push("commandRewards");
            commandRewardsEnabled = b.define("enabled", false);
            allowedRoots = b.comment("Only these command words may be used. Commands like op, stop, execute, function are always refused.")
                    .defineListAllowEmpty("allowedRoots", List.of("give", "xp", "experience", "effect", "tellraw", "title",
                            "playsound", "advancement", "recipe", "loot"), o -> o instanceof String);
            allowSelectors = b.comment("Allow @a, @e, @p, @r, @n (only @s and {player} are allowed when false)").define("allowSelectors", false);
            commandPermissionLevel = b.comment("Permission level the command runs with").defineInRange("permissionLevel", 2, 0, 4);
            b.pop();

            b.push("objectives");
            ignoreCreativePlayers = b.comment("Do not count progress made in creative mode").define("ignoreCreativePlayers", false);
            haveItemFallbackTicks = b.comment("Safety-net inventory check for 'have item' objectives, only for players that have such an",
                    "objective active (0 = off; events and container-close checks always run)").defineInRange("haveItemFallbackTicks", 100, 0, 12000);
            locationCheckTicks = b.comment("How often players with an active location objective are checked")
                    .defineInRange("locationCheckTicks", 20, 5, 200);
            b.pop();

            b.push("network");
            sendNotifications = b.comment("Send notification events to clients").define("sendNotifications", true);
            maxActionsPerSecond = b.comment("Per-player limit of journal/editor requests").defineInRange("maxActionsPerSecond", 20, 1, 200);
            b.pop();

            b.push("packs");
            installExamplePack = b.comment("Copy the vanilla example pack the first time the packs folder is created")
                    .define("installExamplePack", true);
            maxQuestFileKb = b.comment("Quest files larger than this are rejected").defineInRange("maxQuestFileKb", 1024, 16, 65536);
            importMaxArchiveMb = b.defineInRange("importMaxArchiveMb", 64, 1, 2048);
            importMaxTotalMb = b.comment("Maximum uncompressed size of an imported archive").defineInRange("importMaxTotalMb", 256, 1, 4096);
            importMaxEntries = b.defineInRange("importMaxEntries", 50000, 1, 1_000_000);
            b.pop();
        }
    }
}
