import java.util.*;
import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.model.*;
import com.questjournal.core.registry.QuestRegistry;
import com.questjournal.core.state.*;
import com.questjournal.core.validate.*;

/** Micro-benchmark of the Minecraft-independent core (sandbox only; no Minecraft involved). */
public class Bench {
    static long t0;
    static void start() { t0 = System.nanoTime(); }
    static String ms() { return String.format("%.1f ms", (System.nanoTime() - t0) / 1e6); }

    public static void main(String[] a) throws Exception {
        int N = Integer.parseInt(a.length > 0 ? a[0] : "20000");
        boolean flat = a.length > 1 && a[1].equals("flat");   // flat: no prerequisites, everything auto-starts (worst case)
        Runtime rt = Runtime.getRuntime();
        System.gc(); long before = rt.totalMemory() - rt.freeMemory();
        List<QuestDefinition> quests = new ArrayList<>();
        String[] kills = {"minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider"};
        for (int i = 0; i < N; i++) {
            QuestDefinition q = new QuestDefinition();
            q.id = "bench:q" + i; q.title = "Benchmark quest number " + i; q.shortDescription = "Short text " + i;
            q.description = "A longer description for quest " + i + " that talks about zombies, castles and dragons. ".repeat(4);
            q.category = QuestCategory.values()[i % 4];
            q.acceptance = flat ? AcceptanceMode.AUTO : (i % 3 == 0) ? AcceptanceMode.MANUAL : AcceptanceMode.AUTO;
            if (!flat && i >= 4) q.prerequisites.add("bench:q" + (i - 4));          // four long chains
            for (int k = 0; k < 3; k++) {
                ObjectiveDefinition o = new ObjectiveDefinition();
                o.id = "o" + k; o.type = k == 0 ? ObjectiveType.KILL_ENTITY : k == 1 ? ObjectiveType.OBTAIN_ITEM : ObjectiveType.BREAK_BLOCK;
                o.target = k == 0 ? kills[i % 4] : k == 1 ? "minecraft:diamond" : "minecraft:stone"; o.count = 5; q.objectives.add(o);
            }
            RewardDefinition r = new RewardDefinition(); r.id = "r1"; r.type = RewardType.ITEM; r.item = "minecraft:bread"; r.count = 3; q.rewards.add(r);
            quests.add(q);
        }
        System.out.println("Quests: " + N);
        start(); ValidationReport rep = QuestValidator.validate(quests, RegistryChecker.ACCEPT_ALL, null, null);
        System.out.println("validate (prereqs, cycle detection, refs): " + ms() + ", issues=" + rep.issues().size());
        start(); QuestRegistry reg = QuestRegistry.build(1, quests, new HashMap<>(), rep);
        System.out.println("build registry + indexes: " + ms());
        System.gc(); long after = rt.totalMemory() - rt.freeMemory();
        System.out.println("heap used by definitions + registry: ~" + (after - before) / (1024 * 1024) + " MB");

        ProgressEngine eng = new ProgressEngine(reg);
        PlayerProgress p = new PlayerProgress(UUID.randomUUID());
        start(); Changes ch = new Changes(); eng.evaluateAll(p, 1, ch);
        System.out.println("evaluateAll for one player (login): " + ms() + ", records=" + p.quests.size());
        start(); ActiveIndex idx = eng.buildIndex(p);
        System.out.println("buildIndex (active objectives of that player): " + ms());

        // hot path: a kill event for a player who has active kill objectives
        int hits = 0; start();
        for (int i = 0; i < 1_000_000; i++) hits += idx.get(ObjectiveType.KILL_ENTITY, "minecraft:zombie").size();
        System.out.println("1,000,000 index lookups (kill event, matching target): " + ms() + " (refs per lookup=" + hits / 1_000_000 + ")");
        Changes dummy = new Changes(); start();
        int lookups = 5_000_000; boolean any = false;
        for (int i = 0; i < lookups; i++) any |= idx.has(ObjectiveType.VISIT_LOCATION);
        System.out.println("5,000,000 irrelevant-event checks (player has no such objective): " + ms() + " any=" + any);

        // one event fanning out to every matching objective (worst case: all quests want zombies)
        List<ActiveIndex.Ref> refs = idx.get(ObjectiveType.KILL_ENTITY, "minecraft:zombie");
        start(); Changes c2 = new Changes();
        for (ActiveIndex.Ref r : refs) eng.advance(p, reg.get(r.questId()), r.objectiveIndex(), 1, 2, c2);
        System.out.println("one kill advancing " + refs.size() + " objectives at once: " + ms());

        start(); long bytes = 0; for (QuestDefinition q : quests) bytes += QuestCodec.clientJson(q).length();
        System.out.println("client JSON for all quests: " + ms() + ", " + bytes / 1024 + " KB total (~" + bytes / N + " bytes/quest)");
        start(); long b2 = 0; for (QuestDefinition q : quests) { b2 += QuestCodec.toJson(q, true).length(); }
        System.out.println("pretty file JSON for all quests: " + ms());

        // sync payload: parse what a client receives for 200 quests, N/200 times
        StringBuilder sb = new StringBuilder("["); for (int i = 0; i < 200; i++) { if (i > 0) sb.append(','); sb.append("{\"id\":\"x\",\"def\":").append(QuestCodec.clientJson(quests.get(i))).append(",\"st\":{\"s\":\"active\",\"t\":1,\"o\":[[1,0,1],[0,0,1],[0,0,1]]}}"); }
        String batch = sb.append(']').toString();
        start(); for (int i = 0; i < N / 200; i++) Json.parse(batch);
        System.out.println("client-side parse of the full sync (" + N / 200 + " batches of 200, " + batch.length() / 1024 + " KB each): " + ms());

        // client search: build haystacks once, then filter on every keystroke
        start(); String[] hay = new String[N]; for (int i = 0; i < N; i++) { QuestDefinition q = quests.get(i); hay[i] = (q.title + "\n" + q.shortDescription + "\n" + q.description + "\n" + q.id).toLowerCase(); }
        System.out.println("search index build (first search only): " + ms());
        start(); int found = 0; for (int rep2 = 0; rep2 < 20; rep2++) { found = 0; for (String h : hay) if (h.contains("number 1999")) found++; }
        System.out.println("one search keystroke over " + N + " quests (avg of 20): " + String.format("%.2f ms", (System.nanoTime() - t0) / 1e6 / 20) + ", matches=" + found);
    }
}
