package com.questjournal.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.questjournal.QuestJournalMod;
import com.questjournal.config.QJConfig;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.pack.LoadedQuest;
import com.questjournal.core.pack.PackInfo;
import com.questjournal.core.pack.PackWriter;
import com.questjournal.core.security.CommandPolicy;
import com.questjournal.core.util.Ids;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.ValidationReport;
import com.questjournal.network.Kinds;
import com.questjournal.network.Net;

import net.minecraft.server.level.ServerPlayer;

/**
 * Operator-only operations used by the in-game editor. EVERY operation re-checks the sender's permission on the
 * server; client-submitted quest JSON is parsed and validated here exactly like a file on disk, and a client can never
 * make the server run a command (commands in published quests need policy approval, see Rewards).
 */
final class AdminService {
    private AdminService() {
    }

    private static String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String ? (String) m.get(key) : "";
    }

    static void handle(QuestService svc, ServerPlayer p, Map<String, Object> m) {
        if (!Admin.isAdmin(p)) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] {} tried an administrative action without permission and was refused.", p.getName().getString());
            svc.sendResult(p, false, "You are not allowed to edit quests.", "");
            return;
        }
        Consumer<String> say = msg -> svc.sendResult(p, true, msg, "");
        switch (str(m, "op")) {
            case "open": openEditor(svc, p); break;
            case "reload": svc.reload(say, true); break;
            case "validate": svc.reload(say, false); break;
            case "publish": publish(svc, p, m); break;
            case "delete": delete(svc, p, str(m, "id")); break;
            case "approve": approve(svc, p, str(m, "id")); break;
            case "save_draft": saveDraft(svc, p, m); break;
            case "delete_draft": deleteDraft(svc, p, str(m, "id")); break;
            default: svc.sendResult(p, false, "Unknown editor operation.", "");
        }
    }

    // ------------------------------------------------------------------ editor snapshot

    private static void openEditor(QuestService svc, ServerPlayer p) {
        final List<LoadedQuest> loaded = new ArrayList<>(svc.lastLoad().quests);
        final List<PackInfo> packs = new ArrayList<>(svc.lastLoad().packs);
        final ValidationReport report = svc.registry().report;
        final ForgeRegistryChecker checker = new ForgeRegistryChecker(svc.server);
        svc.io.execute(() -> {
            try {
                Map<String, Object> root = new LinkedHashMap<>();
                List<Object> packList = new ArrayList<>();
                for (PackInfo pi : packs) {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", pi.id());
                    pm.put("name", pi.name());
                    pm.put("stale", pi.stale());
                    packList.add(pm);
                }
                root.put("packs", packList);

                // Raw file text of every quest file (including files that failed to load, so they can be fixed in the editor).
                List<Object> quests = new ArrayList<>();
                for (PackInfo pi : packs) {
                    Path qd = pi.dir().resolve("quests");
                    if (!Files.isDirectory(qd)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(qd, 10)) {
                        for (Path f : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".json") && Files.isRegularFile(x)
                                && !pi.dir().relativize(x).toString().replace('\\', '/').contains("/."))::iterator) {
                            if (Files.size(f) > svc.maxFileBytes()) {
                                continue;
                            }
                            Map<String, Object> qm = new LinkedHashMap<>();
                            qm.put("pack", pi.id());
                            qm.put("file", pi.dir().relativize(f).toString().replace('\\', '/'));
                            qm.put("json", Files.readString(f, StandardCharsets.UTF_8));
                            quests.add(qm);
                        }
                    }
                }
                root.put("quests", quests);

                List<Object> drafts = new ArrayList<>();
                for (Map.Entry<String, String> e : PackWriter.listDrafts(svc.draftsDir, svc.maxFileBytes()).entrySet()) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("name", e.getKey());
                    dm.put("json", e.getValue());
                    drafts.add(dm);
                }
                root.put("drafts", drafts);

                List<Object> issues = new ArrayList<>();
                for (Issue i : report.issues()) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("sev", i.severity().name());
                    im.put("code", i.code());
                    im.put("quest", i.questId());
                    im.put("file", i.file());
                    im.put("path", i.path());
                    im.put("msg", i.message());
                    im.put("blocking", i.blocking());
                    issues.add(im);
                }
                root.put("issues", issues);
                root.put("dimensions", new ArrayList<Object>(new java.util.TreeSet<>(checker.dimensionIds())));
                root.put("commandsEnabled", QJConfig.COMMON.commandRewardsEnabled.get());
                List<Object> approved = new ArrayList<>();
                for (LoadedQuest lq : loaded) {
                    for (RewardDefinition r : lq.def().rewards) {
                        String fp = CommandPolicy.fingerprint(lq.def().id, r.id, r.command);
                        if (r.type == RewardType.COMMAND && svc.approvals.isApproved(fp)) {
                            approved.add(fp);
                        }
                    }
                }
                root.put("approved", approved);
                String json = Json.write(root, false);
                svc.server.execute(() -> Net.toPlayer(p, Kinds.EDITOR_DATA, json));
            } catch (IOException | RuntimeException e) {
                QuestJournalMod.LOGGER.error("[QuestJournal] Could not build the editor data", e);
                svc.server.execute(() -> svc.sendResult(p, false, "Could not open the editor: " + e.getMessage(), ""));
            }
        });
    }

    // ------------------------------------------------------------------ publish / delete / approve / drafts

    @SuppressWarnings("unchecked")
    private static void publish(QuestService svc, ServerPlayer p, Map<String, Object> m) {
        QuestCodec.ParseResult pr = QuestCodec.parseValue(m.get("quest"), "editor");
        if (!pr.ok()) {
            StringBuilder sb = new StringBuilder("The quest has errors and was not published:");
            int n = 0;
            for (Issue i : pr.issues()) {
                if (i.severity() == com.questjournal.core.validate.Severity.ERROR && n++ < 6) {
                    sb.append("\n- ").append(i.path()).append(": ").append(i.message());
                }
            }
            svc.sendResult(p, false, sb.toString(), "");
            return;
        }
        final QuestDefinition def = pr.quest();
        boolean force = Boolean.TRUE.equals(m.get("force"));

        // Validate in context: the whole pack set with this quest replaced.
        List<QuestDefinition> all = new ArrayList<>();
        for (QuestDefinition q : svc.registry().all()) {
            if (!q.id.equals(def.id)) {
                all.add(q);
            }
        }
        all.add(def);
        ValidationReport rep = QuestValidator.validate(all, new ForgeRegistryChecker(svc.server), QJConfig.commandPolicy(), svc.approvals);
        StringBuilder blocking = new StringBuilder();
        for (Issue i : rep.forQuest(def.id)) {
            if (i.blocking()) {
                blocking.append("\n- ").append(i.path()).append(": ").append(i.message());
            }
        }
        if (blocking.length() > 0 && !force) {
            svc.sendResult(p, false, "Validation found problems that stop players from using this quest. Fix them, or publish anyway:" + blocking, "needs_force");
            return;
        }

        // Editing an existing quest always overwrites its own file; new quests go to the requested pack.
        String pack = str(m, "pack");
        String file = str(m, "file");
        for (LoadedQuest lq : svc.lastLoad().quests) {
            if (lq.def().id.equals(def.id)) {
                pack = lq.packId();
                file = lq.relPath();
            }
        }
        if (!Ids.isPackId(pack)) {
            pack = "server_quests";
        }
        final String packId = pack;
        final String relPath = file;
        final String who = p.getGameProfile().getName();
        svc.io.execute(() -> {
            try {
                PackWriter.saveQuest(svc.packsDir, packId, relPath, def);
                PackWriter.deleteDraft(svc.draftsDir, def.id);
                // A command reward written by an operator through the editor is approved on their behalf, but only
                // if it passes the allowlist/safety policy. Imported or hand-edited packs always need /questjournal approve.
                CommandPolicy policy = QJConfig.commandPolicy();
                for (RewardDefinition r : def.rewards) {
                    if (r.type == RewardType.COMMAND && policy.check(r.command).isEmpty()) {
                        svc.approvals.approve(CommandPolicy.fingerprint(def.id, r.id, r.command), def.id, r.id, who, System.currentTimeMillis());
                    }
                }
                svc.server.execute(() -> {
                    QuestJournalMod.LOGGER.info("[QuestJournal] {} published quest {} to pack {}", who, def.id, packId);
                    svc.reload(msg -> svc.sendResult(p, true, "Published " + def.id + ". " + msg, def.id), true);
                });
            } catch (IOException e) {
                svc.server.execute(() -> svc.sendResult(p, false, "Could not save the quest: " + e.getMessage(), ""));
            }
        });
    }

    private static void delete(QuestService svc, ServerPlayer p, String questId) {
        for (LoadedQuest lq : svc.lastLoad().quests) {
            if (lq.def().id.equals(questId)) {
                svc.io.execute(() -> {
                    try {
                        PackWriter.trashQuest(svc.packsDir, lq.packId(), lq.relPath());
                        svc.server.execute(() -> svc.reload(msg -> svc.sendResult(p, true, "Moved " + questId + " to the pack's .trash folder. " + msg, ""), true));
                    } catch (IOException e) {
                        svc.server.execute(() -> svc.sendResult(p, false, "Could not delete: " + e.getMessage(), ""));
                    }
                });
                return;
            }
        }
        svc.sendResult(p, false, "That quest is not loaded (a draft can be removed with Delete draft).", "");
    }

    static String approveQuest(QuestService svc, QuestDefinition q, String approvedBy) throws IOException {
        CommandPolicy policy = QJConfig.commandPolicy();
        int ok = 0;
        StringBuilder refused = new StringBuilder();
        for (RewardDefinition r : q.rewards) {
            if (r.type != RewardType.COMMAND) {
                continue;
            }
            List<String> why = policy.check(r.command);
            if (why.isEmpty()) {
                svc.approvals.approve(CommandPolicy.fingerprint(q.id, r.id, r.command), q.id, r.id, approvedBy, System.currentTimeMillis());
                ok++;
            } else {
                refused.append(" Reward ").append(r.id).append(" refused: ").append(why.get(0)).append('.');
            }
        }
        QuestJournalMod.LOGGER.info("[QuestJournal] {} approved {} command reward(s) of {}", approvedBy, ok, q.id);
        return "Approved " + ok + " command reward(s) of " + q.id + "." + refused
                + (QJConfig.COMMON.commandRewardsEnabled.get() ? "" : " Note: command rewards are still disabled in the server config.");
    }

    private static void approve(QuestService svc, ServerPlayer p, String questId) {
        QuestDefinition q = svc.registry().get(questId);
        if (q == null) {
            svc.sendResult(p, false, "Unknown quest.", "");
            return;
        }
        try {
            String msg = approveQuest(svc, q, p.getGameProfile().getName());
            svc.reload(r -> { }, true);
            svc.sendResult(p, true, msg, questId);
        } catch (IOException e) {
            svc.sendResult(p, false, "Could not save approvals: " + e.getMessage(), "");
        }
    }

    private static void saveDraft(QuestService svc, ServerPlayer p, Map<String, Object> m) {
        Map<String, Object> q = Json.asObject(m.get("quest"));
        String id = q == null ? "" : str(q, "id");
        String text = Json.write(q, true);
        if (!Ids.isQuestId(id) || text.length() > svc.maxFileBytes()) {
            svc.sendResult(p, false, "A draft needs a valid quest id (namespace:path) and must be smaller than the file limit.", "");
            return;
        }
        svc.io.execute(() -> {
            try {
                PackWriter.saveDraft(svc.draftsDir, id, text);
                svc.server.execute(() -> svc.sendResult(p, true, "Draft saved on the server. It is NOT live until you publish it.", id));
            } catch (IOException e) {
                svc.server.execute(() -> svc.sendResult(p, false, "Could not save the draft: " + e.getMessage(), ""));
            }
        });
    }

    private static void deleteDraft(QuestService svc, ServerPlayer p, String id) {
        svc.io.execute(() -> {
            try {
                boolean gone = PackWriter.deleteDraft(svc.draftsDir, id);
                svc.server.execute(() -> svc.sendResult(p, gone, gone ? "Draft deleted." : "No such draft.", id));
            } catch (IOException e) {
                svc.server.execute(() -> svc.sendResult(p, false, "Could not delete the draft: " + e.getMessage(), ""));
            }
        });
    }
}
