package com.questjournal.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.questjournal.QuestJournalMod;
import com.questjournal.config.QJConfig;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.pack.LoadResult;
import com.questjournal.core.pack.LoadedQuest;
import com.questjournal.core.pack.PackLoader;
import com.questjournal.core.registry.QuestRegistry;
import com.questjournal.core.security.ApprovalStore;
import com.questjournal.core.state.ActiveIndex;
import com.questjournal.core.state.Changes;
import com.questjournal.core.state.Notice;
import com.questjournal.core.state.NoticeType;
import com.questjournal.core.state.ObjectiveProgress;
import com.questjournal.core.state.ProgressEngine;
import com.questjournal.core.state.ProgressStore;
import com.questjournal.core.state.QuestProgress;
import com.questjournal.core.state.Result;
import com.questjournal.core.validate.Issue;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.Severity;
import com.questjournal.core.validate.ValidationReport;
import com.questjournal.network.Kinds;
import com.questjournal.network.Net;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Server-side owner of quest definitions, player progress, objective events, rewards and synchronisation.
 * All state is mutated on the server thread; the only background work is file I/O (pack scanning, saves).
 * Game events never scan quests: they do one lookup in the player's {@link ActiveIndex}.
 */
public final class QuestService {
    private static QuestService instance;

    public static QuestService get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        instance = new QuestService(server);
        instance.init();
    }

    public static void stop() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    public final MinecraftServer server;
    public final Path root;
    public final Path packsDir;
    public final Path importDir;
    public final Path exportDir;
    public final Path draftsDir;
    public final Path backupsDir;
    public final Path stagingDir;
    public final ApprovalStore approvals;
    final ProgressStore store;
    final ExecutorService io;
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile QuestRegistry registry = QuestRegistry.EMPTY;
    private ProgressEngine engine = new ProgressEngine(QuestRegistry.EMPTY);
    private LoadResult lastLoad = new LoadResult();
    private int generation;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Set<UUID> dirtyPlayers = new LinkedHashSet<>();
    private final Set<UUID> noticePlayers = new LinkedHashSet<>();
    private final Set<UUID> locationWatchers = new HashSet<>();
    private final Set<UUID> haveWatchers = new HashSet<>();
    /** Tiny LRU of entity UUIDs already credited, so one death can never count twice. */
    private final Map<UUID, Boolean> recentDeaths = new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > 512;
        }
    };

    private QuestService(MinecraftServer server) {
        this.server = server;
        this.root = FMLPaths.CONFIGDIR.get().resolve("questjournal");
        this.packsDir = root.resolve("packs");
        this.importDir = root.resolve("import");
        this.exportDir = root.resolve("export");
        this.draftsDir = root.resolve("drafts");
        this.backupsDir = root.resolve("backups");
        this.stagingDir = root.resolve(".staging");
        this.approvals = new ApprovalStore(root.resolve("approved_commands.json"));
        this.store = new ProgressStore(server.getWorldPath(LevelResource.ROOT).resolve("questjournal").resolve("players"),
                msg -> QuestJournalMod.LOGGER.error("[QuestJournal] {}", msg));
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "QuestJournal-Packs");
            t.setDaemon(true);
            return t;
        });
    }

    private void init() {
        try {
            Files.createDirectories(importDir);
            Files.createDirectories(exportDir);
            if (!Files.exists(packsDir) && QJConfig.COMMON.installExamplePack.get()) {
                installExamplePack();
            }
            Files.createDirectories(packsDir);
            approvals.load();
        } catch (IOException | JsonException e) {
            QuestJournalMod.LOGGER.error("[QuestJournal] Could not prepare {}: {}", root, e.toString());
        }
        install(PackLoader.load(packsDir, new HashMap<>(), new HashMap<>(), maxFileBytes()), null); // startup: blocking is fine
    }

    private void installExamplePack() throws IOException {
        try (InputStream idx = QuestService.class.getResourceAsStream("/examples/index.txt")) {
            if (idx == null) {
                return;
            }
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String rel = line.trim();
                if (rel.isEmpty() || rel.contains("..")) {
                    continue;
                }
                try (InputStream in = QuestService.class.getResourceAsStream("/examples/questjournal_examples/" + rel)) {
                    if (in != null) {
                        Path dest = packsDir.resolve("questjournal_examples").resolve(rel);
                        Files.createDirectories(dest.getParent());
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            QuestJournalMod.LOGGER.info("[QuestJournal] Installed the example quest pack into {}", packsDir);
        }
    }

    private void shutdown() {
        for (PlayerData d : players.values()) {
            if (d.progress.dirty) {
                store.saveNow(d.progress);
            }
        }
        store.close();
        io.shutdownNow();
        players.clear();
    }

    long maxFileBytes() {
        return QJConfig.COMMON.maxQuestFileKb.get() * 1024L;
    }

    public QuestRegistry registry() {
        return registry;
    }

    public ProgressEngine engine() {
        return engine;
    }

    public LoadResult lastLoad() {
        return lastLoad;
    }

    PlayerData data(ServerPlayer p) {
        return players.get(p.getUUID());
    }

    static long now() {
        return System.currentTimeMillis();
    }

    // ================================================================== reload

    /** Re-reads all packs on a background thread, then applies (or just validates) on the server thread. */
    public void reload(Consumer<String> feedback, boolean apply) {
        if (!reloading.compareAndSet(false, true)) {
            feedback.accept("A reload is already running.");
            return;
        }
        final Map<String, LoadedQuest> files = lastLoad.fileMap();
        final Map<String, com.questjournal.core.pack.PackInfo> packs = lastLoad.packMap();
        final long max = maxFileBytes();
        CompletableFuture.supplyAsync(() -> PackLoader.load(packsDir, files, packs, max), io)
                .whenComplete((res, err) -> server.execute(() -> {
                    try {
                        if (err != null) {
                            QuestJournalMod.LOGGER.error("[QuestJournal] Reload failed", err);
                            feedback.accept("Reload failed: " + err + ". The previous quests stay active.");
                        } else if (apply) {
                            install(res, feedback);
                        } else {
                            ValidationReport rep = buildReport(res);
                            feedback.accept("Validated " + res.quests.size() + " quest(s) in " + res.packs.size() + " pack(s): " + rep.summary() + " (nothing was changed).");
                            for (String l : rep.toLines(12)) {
                                feedback.accept(l);
                            }
                        }
                    } catch (RuntimeException e) {
                        QuestJournalMod.LOGGER.error("[QuestJournal] Applying the reload failed; keeping the previous quests", e);
                        feedback.accept("Reload failed while applying: " + e + ". The previous quests stay active.");
                    } finally {
                        reloading.set(false);
                    }
                }));
    }

    private ValidationReport buildReport(LoadResult r) {
        List<QuestDefinition> defs = new ArrayList<>();
        for (LoadedQuest q : r.quests) {
            defs.add(q.def());
        }
        ValidationReport full = new ValidationReport();
        full.addAll(r.issues);
        full.addAll(QuestValidator.validate(defs, new ForgeRegistryChecker(server), QJConfig.commandPolicy(), approvals).issues());
        return full;
    }

    /** Builds the new registry completely, then swaps one reference. Runs on the server thread. */
    void install(LoadResult r, Consumer<String> feedback) {
        ValidationReport full = buildReport(r);
        List<QuestDefinition> defs = new ArrayList<>();
        for (LoadedQuest q : r.quests) {
            defs.add(q.def());
        }
        QuestRegistry old = registry;
        QuestRegistry fresh = QuestRegistry.build(++generation, defs, r.packOfQuest(), full);
        warnAboutChangedObjectives(old, fresh, full);
        registry = fresh;
        engine = new ProgressEngine(fresh);
        lastLoad = r;
        for (UUID id : new ArrayList<>(players.keySet())) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            PlayerData d = players.get(id);
            if (sp != null && d != null) {
                Changes ch = new Changes();
                engine.reconcile(d.progress, now(), ch);
                apply(sp, d, ch);
                sendFull(sp, d);
            }
        }
        String summary = "Quest Journal: " + fresh.size() + " quest(s) from " + r.packs.size() + " pack(s), " + full.summary() + ".";
        QuestJournalMod.LOGGER.info("[QuestJournal] {}", summary);
        for (Issue i : full.issues()) {
            if (i.severity() == Severity.ERROR) {
                QuestJournalMod.LOGGER.warn("[QuestJournal] {}", i.format()); // warnings/info go to last-validation.txt
            }
        }
        writeReport(full);
        if (feedback != null) {
            feedback.accept(summary);
            for (String l : full.toLines(10)) {
                feedback.accept(l);
            }
        }
    }

    private void warnAboutChangedObjectives(QuestRegistry old, QuestRegistry fresh, ValidationReport rep) {
        for (QuestDefinition nq : fresh.all()) {
            QuestDefinition oq = old.get(nq.id);
            if (oq == null) {
                continue;
            }
            for (ObjectiveDefinition no : nq.objectives) {
                int i = oq.indexOfObjective(no.id);
                if (i < 0) {
                    continue;
                }
                ObjectiveDefinition oo = oq.objectives.get(i);
                if (oo.type != no.type || !oo.target.equals(no.target)) {
                    rep.add(Issue.warn("objective_changed", nq.id, "objectives[" + nq.indexOfObjective(no.id) + "]",
                            "Objective '" + no.id + "' changed type/target; saved player progress is kept and now counts toward the new definition. "
                                    + "Use /questjournal reset if that is not what you want."));
                } else if (oo.requiredCount() != no.requiredCount()) {
                    rep.add(Issue.info("objective_count_changed", nq.id, "objectives[" + nq.indexOfObjective(no.id) + "].count",
                            "Objective '" + no.id + "' target count changed from " + oo.requiredCount() + " to " + no.requiredCount() + "."));
                }
            }
        }
    }

    private void writeReport(ValidationReport rep) {
        final List<String> lines = rep.toLines(5000);
        io.execute(() -> {
            try {
                Files.createDirectories(root);
                Files.write(root.resolve("last-validation.txt"), lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                QuestJournalMod.LOGGER.warn("[QuestJournal] Could not write last-validation.txt: {}", e.toString());
            }
        });
    }

    // ================================================================== players

    public void onLogin(ServerPlayer p) {
        PlayerData d = new PlayerData(p.getUUID(), store.load(p.getUUID()));
        players.put(p.getUUID(), d);
        Changes ch = new Changes();
        engine.reconcile(d.progress, now(), ch);
        apply(p, d, ch);
        sendFull(p, d);
    }

    public void onLogout(ServerPlayer p) {
        PlayerData d = players.remove(p.getUUID());
        dirtyPlayers.remove(p.getUUID());
        noticePlayers.remove(p.getUUID());
        locationWatchers.remove(p.getUUID());
        haveWatchers.remove(p.getUUID());
        if (d != null) {
            store.saveAsync(d.progress);
        }
    }

    /** Admin utility: forget progress for one quest (or "all") and resync the player. Returns how many entries were removed. */
    public int reset(ServerPlayer p, String questId) {
        PlayerData d = players.get(p.getUUID());
        if (d == null) {
            return 0;
        }
        int n = 0;
        if (questId.equals("all")) {
            n = d.progress.quests.size();
            d.progress.quests.clear();
        } else if (d.progress.quests.remove(questId) != null) {
            n = 1;
        }
        d.progress.dirty = true;
        Changes ch = new Changes();
        engine.reconcile(d.progress, now(), ch);
        apply(p, d, ch);
        sendFull(p, d);
        store.saveNow(d.progress);
        return n;
    }

    // ================================================================== applying changes

    /** Rebuilds the index, queues sync + notifications and runs follow-up presence checks for newly active objectives. */
    private void apply(ServerPlayer p, PlayerData d, Changes ch) {
        int guard = 0;
        while (true) {
            if (ch.indexDirty) {
                d.index = engine.buildIndex(d.progress);
                watch(locationWatchers, d, ObjectiveType.VISIT_LOCATION);
                watch(haveWatchers, d, ObjectiveType.HAVE_ITEM);
            }
            if (!ch.dirtyQuests.isEmpty()) {
                d.dirty.addAll(ch.dirtyQuests);
                dirtyPlayers.add(d.uuid);
            }
            queueNotices(d, ch.notices);
            if (ch.activated.isEmpty() || ++guard > 8) {
                return;
            }
            Changes next = new Changes();
            collectPresence(p, d, next);
            if (next.isEmpty()) {
                return;
            }
            ch = next;
        }
    }

    private void watch(Set<UUID> set, PlayerData d, ObjectiveType type) {
        if (d.index.has(type)) {
            set.add(d.uuid);
        } else {
            set.remove(d.uuid);
        }
    }

    private void queueNotices(PlayerData d, List<Notice> notices) {
        if (notices.isEmpty() || !QJConfig.COMMON.sendNotifications.get()) {
            return;
        }
        for (Notice n : notices) {
            boolean objective = n.type() == NoticeType.OBJECTIVE_PROGRESS || n.type() == NoticeType.OBJECTIVE_COMPLETE;
            String key = objective ? "o|" + n.questId() + "|" + n.objectiveId() : n.type() + "|" + n.questId();
            d.pending.put(key, n); // a later event for the same thing replaces the earlier one
        }
        noticePlayers.add(d.uuid);
    }

    // ================================================================== objective events (all server side)

    private ResourceLocation keyOf(Item item) {
        return ForgeRegistries.ITEMS.getKey(item);
    }

    private boolean skip(ServerPlayer p) {
        return QJConfig.COMMON.ignoreCreativePlayers.get() && p.isCreative();
    }

    /** Adds {@code amount} to every active objective of this type/target for the player. One map lookup; no quest scans. */
    private void fire(ServerPlayer p, PlayerData d, ObjectiveType type, String target, int amount) {
        List<ActiveIndex.Ref> refs = d.index.get(type, target);
        if (refs.isEmpty() || skip(p)) {
            return;
        }
        Changes ch = new Changes();
        for (ActiveIndex.Ref r : new ArrayList<>(refs)) {
            QuestDefinition q = registry.get(r.questId());
            if (q != null) {
                engine.advance(d.progress, q, r.objectiveIndex(), amount, now(), ch);
            }
        }
        apply(p, d, ch);
    }

    public void onKill(ServerPlayer killer, LivingEntity victim) {
        PlayerData d = players.get(killer.getUUID());
        if (d == null || !d.index.has(ObjectiveType.KILL_ENTITY) || recentDeaths.put(victim.getUUID(), Boolean.TRUE) != null) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
        if (id != null) {
            fire(killer, d, ObjectiveType.KILL_ENTITY, id.toString(), 1);
        }
    }

    /** Pickup, crafting and smelting all report items gained; also re-checks "have item" objectives for that item. */
    public void onItemGained(ServerPlayer p, ItemStack stack) {
        PlayerData d = players.get(p.getUUID());
        if (d == null || stack.isEmpty()) {
            return;
        }
        boolean obtain = d.index.has(ObjectiveType.OBTAIN_ITEM);
        boolean have = d.index.has(ObjectiveType.HAVE_ITEM);
        if (!obtain && !have) {
            return;
        }
        ResourceLocation id = keyOf(stack.getItem());
        if (id == null) {
            return;
        }
        if (obtain) {
            fire(p, d, ObjectiveType.OBTAIN_ITEM, id.toString(), stack.getCount());
        }
        if (have && !d.index.get(ObjectiveType.HAVE_ITEM, id.toString()).isEmpty()) {
            checkHave(p, d);
        }
    }

    public void onContainerClosed(ServerPlayer p) {
        PlayerData d = players.get(p.getUUID());
        if (d != null && d.index.has(ObjectiveType.HAVE_ITEM)) {
            checkHave(p, d);
        }
    }

    public void onBlockBroken(ServerPlayer p, BlockState state) {
        PlayerData d = players.get(p.getUUID());
        if (d != null && d.index.has(ObjectiveType.BREAK_BLOCK)) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (id != null) {
                fire(p, d, ObjectiveType.BREAK_BLOCK, id.toString(), 1);
            }
        }
    }

    public void onBlockInteracted(ServerPlayer p, BlockState state, BlockPos pos) {
        PlayerData d = players.get(p.getUUID());
        if (d == null || !d.index.has(ObjectiveType.INTERACT_BLOCK)) {
            return;
        }
        long tick = server.getTickCount();
        if (d.lastInteractTick == tick && d.lastInteractPos == pos.asLong()) {
            return; // the same click reported twice in one tick
        }
        d.lastInteractTick = tick;
        d.lastInteractPos = pos.asLong();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id != null) {
            fire(p, d, ObjectiveType.INTERACT_BLOCK, id.toString(), 1);
        }
    }

    public void onDimensionChanged(ServerPlayer p, ResourceKey<Level> to) {
        PlayerData d = players.get(p.getUUID());
        if (d != null && d.index.has(ObjectiveType.VISIT_DIMENSION)) {
            fire(p, d, ObjectiveType.VISIT_DIMENSION, to.location().toString(), 1);
        }
    }

    // ---- state based objectives (inventory possession, dimension/location presence)

    private static int countItem(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private void collectHave(ServerPlayer p, PlayerData d, Changes out) {
        for (Map.Entry<String, List<ActiveIndex.Ref>> e : d.index.byType(ObjectiveType.HAVE_ITEM).entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) {
                continue;
            }
            int have = countItem(p.getInventory(), ForgeRegistries.ITEMS.getValue(rl));
            if (have > 0) {
                for (ActiveIndex.Ref r : e.getValue()) {
                    QuestDefinition q = registry.get(r.questId());
                    if (q != null) {
                        engine.setAtLeast(d.progress, q, r.objectiveIndex(), have, now(), out);
                    }
                }
            }
        }
    }

    private void collectLocation(ServerPlayer p, PlayerData d, Changes out) {
        String dim = p.level().dimension().location().toString();
        for (ActiveIndex.Ref r : d.index.get(ObjectiveType.VISIT_DIMENSION, dim)) {
            QuestDefinition q = registry.get(r.questId());
            if (q != null) {
                engine.setAtLeast(d.progress, q, r.objectiveIndex(), 1, now(), out);
            }
        }
        for (ActiveIndex.Ref r : d.index.get(ObjectiveType.VISIT_LOCATION, dim)) {
            QuestDefinition q = registry.get(r.questId());
            if (q == null) {
                continue;
            }
            ObjectiveDefinition o = q.objectives.get(r.objectiveIndex());
            if (p.distanceToSqr(o.x, o.y, o.z) <= (double) o.radius * o.radius) {
                engine.setAtLeast(d.progress, q, r.objectiveIndex(), 1, now(), out);
            }
        }
    }

    private void collectPresence(ServerPlayer p, PlayerData d, Changes out) {
        if (d.index.has(ObjectiveType.HAVE_ITEM)) {
            collectHave(p, d, out);
        }
        if (d.index.has(ObjectiveType.VISIT_DIMENSION) || d.index.has(ObjectiveType.VISIT_LOCATION)) {
            collectLocation(p, d, out);
        }
    }

    private void checkHave(ServerPlayer p, PlayerData d) {
        Changes ch = new Changes();
        collectHave(p, d, ch);
        if (!ch.isEmpty()) {
            apply(p, d, ch);
        }
    }

    // ================================================================== server tick

    public void tick(int tickCount) {
        if (!dirtyPlayers.isEmpty()) {
            for (UUID id : new ArrayList<>(dirtyPlayers)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                PlayerData d = players.get(id);
                if (p != null && d != null) {
                    sendDelta(p, d);
                }
            }
            dirtyPlayers.clear();
        }
        if (tickCount % 10 == 0 && !noticePlayers.isEmpty()) {
            for (UUID id : new ArrayList<>(noticePlayers)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                PlayerData d = players.get(id);
                if (p != null && d != null) {
                    flushNotices(p, d);
                }
            }
            noticePlayers.clear();
        }
        int li = QJConfig.COMMON.locationCheckTicks.get();
        if (!locationWatchers.isEmpty() && tickCount % li == 0) {
            for (UUID id : new ArrayList<>(locationWatchers)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                PlayerData d = players.get(id);
                if (p != null && d != null) {
                    Changes ch = new Changes();
                    collectLocation(p, d, ch);
                    if (!ch.isEmpty()) {
                        apply(p, d, ch);
                    }
                }
            }
        }
        int hi = QJConfig.COMMON.haveItemFallbackTicks.get();
        if (hi > 0 && !haveWatchers.isEmpty() && tickCount % hi == 0) {
            for (UUID id : new ArrayList<>(haveWatchers)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                PlayerData d = players.get(id);
                if (p != null && d != null) {
                    checkHave(p, d);
                }
            }
        }
        if (tickCount % 6000 == 0) {
            for (PlayerData d : players.values()) {
                store.saveAsync(d.progress);
            }
        }
    }

    // ================================================================== client actions

    boolean allow(PlayerData d) {
        long t = now();
        if (t - d.rateWindowStart > 1000) {
            d.rateWindowStart = t;
            d.rateCount = 0;
        }
        return ++d.rateCount <= QJConfig.COMMON.maxActionsPerSecond.get();
    }

    void handleAction(ServerPlayer p, PlayerData d, Map<String, Object> m) {
        String action = m.get("a") instanceof String ? (String) m.get("a") : "";
        String qid = m.get("q") instanceof String ? (String) m.get("q") : "";
        Changes ch = new Changes();
        Result r;
        switch (action) {
            case "sync":
                sendFull(p, d);
                return;
            case "accept":
                r = engine.accept(d.progress, qid, now(), ch);
                break;
            case "abandon":
                r = engine.abandon(d.progress, qid, now(), ch);
                break;
            case "dialogue":
                r = engine.markDialogueDone(d.progress, qid, m.get("d") instanceof String ? (String) m.get("d") : "", now(), ch);
                break;
            case "submit":
                r = submit(p, d, qid, m.get("o") instanceof String ? (String) m.get("o") : "", ch);
                break;
            case "claim":
                claim(p, d, qid);
                return;
            default:
                return;
        }
        apply(p, d, ch);
        sendResult(p, r.success(), r.message(), qid);
    }

    /** Turn-in: the server counts and removes the items itself; the client only names the objective. */
    private Result submit(ServerPlayer p, PlayerData d, String questId, String objectiveId, Changes ch) {
        QuestDefinition q = registry.get(questId);
        int idx = q == null ? -1 : q.indexOfObjective(objectiveId);
        int need = idx < 0 ? 0 : engine.deliverRemaining(d.progress, q, idx);
        if (need <= 0) {
            return Result.fail("nothing_to_deliver", "That objective does not need a delivery right now.");
        }
        ResourceLocation rl = ResourceLocation.tryParse(q.objectives.get(idx).target);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) {
            return Result.fail("unresolved", "The required item is not available on this server.");
        }
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        Inventory inv = p.getInventory();
        int left = need;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
                if (s.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        int removed = need - left;
        if (removed <= 0) {
            return Result.fail("no_items", "You do not have any of the required items in your inventory.");
        }
        inv.setChanged();
        Result r = engine.advance(d.progress, q, idx, removed, now(), ch);
        return r.success() ? Result.ok() : r;
    }

    private void claim(ServerPlayer p, PlayerData d, String questId) {
        QuestDefinition q = registry.get(questId);
        Result chk = engine.checkClaim(d.progress, q);
        if (!chk.success()) {
            sendResult(p, false, chk.message(), questId);
            return;
        }
        List<RewardDefinition> pending = engine.pendingRewards(d.progress, q);
        String problem = Rewards.precheck(this, p, q, pending);
        if (problem != null) {
            sendResult(p, false, problem, questId);
            return;
        }
        for (RewardDefinition r : pending) {
            String err = Rewards.grant(this, p, q, r);
            if (err != null) {
                store.saveNow(d.progress);
                sendResult(p, false, err + " Rewards already delivered are not repeated; press Claim again to retry.", questId);
                return;
            }
            engine.markRewardGranted(d.progress, q.id, r.id);
        }
        Changes ch = new Changes();
        Result fin = engine.finishClaim(d.progress, q, now(), ch);
        store.saveNow(d.progress); // synchronous on purpose: a crash must not allow a second claim
        apply(p, d, ch);
        sendResult(p, fin.success(), fin.success() ? "Reward claimed!" : fin.message(), questId);
    }

    // ================================================================== sync / messages

    void sendResult(ServerPlayer p, boolean ok, String msg, String questId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("msg", msg);
        m.put("q", questId);
        Net.toPlayer(p, Kinds.RESULT, Json.write(m, false));
    }

    private Map<String, Object> entry(PlayerData d, QuestDefinition q, boolean withDefinition) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", q.id);
        if (withDefinition) {
            e.put("def", new Json.Raw(QuestCodec.clientJson(q)));
        }
        QuestStatus st = engine.statusOf(d.progress, q);
        QuestProgress qp = d.progress.get(q.id);
        boolean hideProgress = st == QuestStatus.ABANDONED && q.abandon == com.questjournal.core.model.AbandonMode.RESET_PROGRESS;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("s", st.name().toLowerCase(Locale.ROOT));
        s.put("t", qp == null ? 0L : qp.since);
        List<Object> objs = new ArrayList<>();
        for (ObjectiveDefinition o : q.objectives) {
            ObjectiveProgress op = qp == null || hideProgress ? null : qp.objectives.get(o.id);
            ObjectiveProgress rv = qp == null ? null : qp.objectives.get(o.id);
            objs.add(List.of((long) (op == null ? 0 : Math.min(op.count, o.requiredCount())),
                    op != null && op.done ? 1L : 0L, rv != null && rv.revealed ? 1L : 0L));
        }
        s.put("o", objs);
        if (qp != null && !qp.dialoguesDone.isEmpty()) {
            s.put("dl", new ArrayList<Object>(qp.dialoguesDone));
        }
        if (st == QuestStatus.INVALID) {
            List<Object> msgs = new ArrayList<>();
            for (Issue i : registry.blockers(q.id)) {
                msgs.add(i.message());
            }
            s.put("inv", msgs);
        }
        e.put("st", s);
        return e;
    }

    void sendFull(ServerPlayer p, PlayerData d) {
        d.clientKnown.clear();
        d.dirty.clear();
        List<Object> batch = new ArrayList<>();
        boolean first = true;
        for (QuestDefinition q : registry.all()) {
            if (!engine.isVisible(d.progress, q)) {
                continue;
            }
            batch.add(entry(d, q, true));
            d.clientKnown.add(q.id);
            if (batch.size() >= 200) {
                sendSync(p, first, false, batch);
                batch = new ArrayList<>();
                first = false;
            }
        }
        sendSync(p, first, true, batch);
    }

    private void sendSync(ServerPlayer p, boolean first, boolean last, List<Object> batch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("first", first);
        m.put("last", last);
        m.put("gen", (long) registry.generation);
        m.put("admin", Admin.isAdmin(p));
        m.put("quests", batch);
        Net.toPlayer(p, Kinds.SYNC, Json.write(m, false));
    }

    private void sendDelta(ServerPlayer p, PlayerData d) {
        List<Object> updates = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        for (String id : d.dirty) {
            QuestDefinition q = registry.get(id);
            if (q != null && engine.isVisible(d.progress, q)) {
                updates.add(entry(d, q, !d.clientKnown.contains(id)));
                d.clientKnown.add(id);
            } else if (d.clientKnown.remove(id)) {
                removed.add(id);
            }
        }
        d.dirty.clear();
        if (updates.isEmpty() && removed.isEmpty()) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updates", updates);
        m.put("removed", removed);
        Net.toPlayer(p, Kinds.DELTA, Json.write(m, false));
    }

    private void flushNotices(ServerPlayer p, PlayerData d) {
        if (d.pending.isEmpty()) {
            return;
        }
        Map<NoticeType, Integer> counts = new java.util.EnumMap<>(NoticeType.class);
        for (Notice n : d.pending.values()) {
            counts.merge(n.type(), 1, Integer::sum);
        }
        List<Object> out = new ArrayList<>();
        Set<NoticeType> summarised = new HashSet<>();
        for (Notice n : d.pending.values()) {
            boolean many = counts.get(n.type()) > 3 && (n.type() == NoticeType.QUEST_AVAILABLE
                    || n.type() == NoticeType.QUEST_STARTED || n.type() == NoticeType.QUEST_CLAIMABLE);
            if (many && !summarised.add(n.type())) {
                continue;
            }
            QuestDefinition q = registry.get(n.questId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", n.type().name().toLowerCase(Locale.ROOT));
            m.put("q", n.questId());
            m.put("title", q == null ? n.questId() : q.title);
            if (many) {
                m.put("many", (long) counts.get(n.type()));
            }
            if (!n.objectiveId().isEmpty() && q != null) {
                int i = q.indexOfObjective(n.objectiveId());
                if (i >= 0) {
                    ObjectiveDefinition o = q.objectives.get(i);
                    m.put("obj", o.description.isEmpty() ? o.autoDescription() : o.description);
                }
                m.put("n", (long) n.count());
                m.put("m", (long) n.target());
            }
            out.add(m);
        }
        d.pending.clear();
        Net.toPlayer(p, Kinds.NOTIFY, Json.write(out, false));
    }
}
