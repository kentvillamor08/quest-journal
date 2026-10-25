package com.questjournal.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.questjournal.QuestJournalMod;
import com.questjournal.config.QJConfig;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.pack.ImportLimits;
import com.questjournal.core.pack.LoadedQuest;
import com.questjournal.core.pack.PackExporter;
import com.questjournal.core.pack.PackImporter;
import com.questjournal.core.pack.PackInfo;
import com.questjournal.core.security.CommandPolicy;
import com.questjournal.network.Kinds;
import com.questjournal.network.Net;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /questjournal ... Everything except "open" requires the editor permission (see Admin). The commands only
 * schedule work; file I/O runs on the QuestJournal background thread and results are reported back on the server thread.
 */
final class QJCommands {
    private QJCommands() {
    }

    private static final SuggestionProvider<CommandSourceStack> PACKS = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        QuestService s = QuestService.get();
        if (s != null) {
            for (PackInfo p : s.lastLoad().packs) {
                ids.add(p.id());
            }
        }
        return SharedSuggestionProvider.suggest(ids, b);
    };

    private static final SuggestionProvider<CommandSourceStack> IMPORT_FILES = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        QuestService s = QuestService.get();
        if (s != null && Files.isDirectory(s.importDir)) {
            try (Stream<Path> files = Files.list(s.importDir)) {
                files.map(f -> f.getFileName().toString()).filter(n -> n.toLowerCase().endsWith(".zip")).forEach(names::add);
            } catch (IOException e) {
                QuestJournalMod.LOGGER.warn("[QuestJournal] Cannot list the import folder: {}", e.toString());
            }
        }
        return SharedSuggestionProvider.suggest(names, b);
    };

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("questjournal")
                .then(Commands.literal("open").executes(c -> open(c.getSource(), false)))
                .then(Commands.literal("editor").requires(Admin::isAdmin).executes(c -> open(c.getSource(), true)))
                .then(Commands.literal("validate").requires(Admin::isAdmin).executes(c -> reload(c.getSource(), false)))
                .then(Commands.literal("reload").requires(Admin::isAdmin).executes(c -> reload(c.getSource(), true)))
                .then(Commands.literal("list").requires(Admin::isAdmin).executes(c -> list(c.getSource())))
                .then(Commands.literal("import").requires(Admin::isAdmin)
                        .then(Commands.argument("filename", StringArgumentType.greedyString()).suggests(IMPORT_FILES)
                                .executes(c -> doImport(c.getSource(), StringArgumentType.getString(c, "filename"), false))))
                .then(Commands.literal("import-overwrite").requires(Admin::isAdmin)
                        .then(Commands.argument("filename", StringArgumentType.greedyString()).suggests(IMPORT_FILES)
                                .executes(c -> doImport(c.getSource(), StringArgumentType.getString(c, "filename"), true))))
                .then(Commands.literal("export").requires(Admin::isAdmin)
                        .then(Commands.argument("pack_id", StringArgumentType.word()).suggests(PACKS)
                                .executes(c -> export(c.getSource(), StringArgumentType.getString(c, "pack_id")))))
                .then(Commands.literal("approve").requires(Admin::isAdmin)
                        .then(Commands.argument("quest_id", StringArgumentType.greedyString())
                                .executes(c -> approve(c.getSource(), StringArgumentType.getString(c, "quest_id"), true))))
                .then(Commands.literal("revoke").requires(Admin::isAdmin)
                        .then(Commands.argument("quest_id", StringArgumentType.greedyString())
                                .executes(c -> approve(c.getSource(), StringArgumentType.getString(c, "quest_id"), false))))
                .then(Commands.literal("reset").requires(Admin::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("quest_id", StringArgumentType.greedyString())
                                        .executes(c -> reset(c.getSource(), EntityArgument.getPlayer(c, "player"),
                                                StringArgumentType.getString(c, "quest_id")))))));
    }

    private static void ok(CommandSourceStack s, String msg) {
        s.sendSuccess(() -> Component.literal(msg), true);
    }

    private static int fail(CommandSourceStack s, String msg) {
        s.sendFailure(Component.literal(msg));
        return 0;
    }

    private static QuestService svc(CommandSourceStack s) {
        QuestService svc = QuestService.get();
        if (svc == null) {
            fail(s, "Quest Journal is not running.");
        }
        return svc;
    }

    private static int open(CommandSourceStack s, boolean editor) throws CommandSyntaxException {
        ServerPlayer p = s.getPlayerOrException();
        Net.toPlayer(p, Kinds.OPEN_JOURNAL, editor ? "{\"editor\":true}" : "{}");
        return 1;
    }

    private static int reload(CommandSourceStack s, boolean apply) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        svc.reload(msg -> ok(s, msg), apply);
        return 1;
    }

    private static int list(CommandSourceStack s) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        if (svc.lastLoad().packs.isEmpty()) {
            ok(s, "No quest packs are installed (" + svc.packsDir + ").");
        }
        for (PackInfo p : svc.lastLoad().packs) {
            int n = 0;
            for (LoadedQuest q : svc.lastLoad().quests) {
                if (q.packId().equals(p.id())) {
                    n++;
                }
            }
            ok(s, "- " + p.id() + " (" + p.name() + (p.version().isEmpty() ? "" : " v" + p.version()) + "): " + n + " quest(s)"
                    + (p.stale() ? " [broken pack.json: last known good version is active]" : ""));
        }
        ok(s, "Total: " + svc.registry().size() + " quest(s). Packs folder: " + svc.packsDir);
        return 1;
    }

    private static int doImport(CommandSourceStack s, String file, boolean overwrite) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        final ImportLimits limits = new ImportLimits(
                QJConfig.COMMON.importMaxArchiveMb.get() * 1024L * 1024L,
                QJConfig.COMMON.importMaxTotalMb.get() * 1024L * 1024L,
                QJConfig.COMMON.maxQuestFileKb.get() * 1024L,
                QJConfig.COMMON.importMaxEntries.get());
        ok(s, "Importing " + file + " ...");
        svc.io.execute(() -> {
            PackImporter.ImportResult r = PackImporter.importZip(svc.importDir, file, svc.packsDir, svc.stagingDir, svc.backupsDir, limits, overwrite);
            svc.server.execute(() -> {
                if (!r.ok()) {
                    fail(s, r.message());
                    return;
                }
                ok(s, r.message());
                for (String w : r.warnings()) {
                    ok(s, "  note: " + w);
                }
                if (!r.questsWithCommands().isEmpty()) {
                    ok(s, "WARNING: these quests contain COMMAND REWARDS and will NOT run until command rewards are enabled in the server config "
                            + "and you review and approve them with /questjournal approve <quest_id>: " + String.join(", ", r.questsWithCommands()));
                }
                svc.reload(msg -> ok(s, msg), true);
            });
        });
        return 1;
    }

    private static int export(CommandSourceStack s, String packId) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        svc.io.execute(() -> {
            try {
                Path zip = PackExporter.export(svc.packsDir, packId, svc.exportDir);
                svc.server.execute(() -> ok(s, "Exported to " + zip + " (command rewards are exported as text; they must be re-approved by whoever imports them)."));
            } catch (IOException e) {
                svc.server.execute(() -> fail(s, "Export failed: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int approve(CommandSourceStack s, String questId, boolean approve) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        QuestDefinition q = svc.registry().get(questId.trim());
        if (q == null) {
            return fail(s, "Unknown quest '" + questId + "'.");
        }
        try {
            String who = s.getTextName();
            if (approve) {
                ok(s, AdminService.approveQuest(svc, q, who));
            } else {
                int n = 0;
                for (RewardDefinition r : q.rewards) {
                    if (r.type == RewardType.COMMAND && svc.approvals.revoke(CommandPolicy.fingerprint(q.id, r.id, r.command))) {
                        n++;
                    }
                }
                ok(s, "Revoked approval of " + n + " command reward(s) of " + q.id + ".");
            }
            svc.reload(msg -> { }, true);
            return 1;
        } catch (IOException e) {
            return fail(s, "Could not save approvals: " + e.getMessage());
        }
    }

    private static int reset(CommandSourceStack s, ServerPlayer target, String questId) {
        QuestService svc = svc(s);
        if (svc == null) {
            return 0;
        }
        int n = svc.reset(target, questId.trim());
        ok(s, "Reset " + n + " quest entr" + (n == 1 ? "y" : "ies") + " for " + target.getName().getString() + ".");
        return 1;
    }
}
