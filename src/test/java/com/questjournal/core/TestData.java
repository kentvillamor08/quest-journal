package com.questjournal.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.questjournal.core.model.AcceptanceMode;
import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.ObjectiveType;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.QuestStatus;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.RewardType;
import com.questjournal.core.registry.QuestRegistry;
import com.questjournal.core.state.Changes;
import com.questjournal.core.state.PlayerProgress;
import com.questjournal.core.state.ProgressEngine;
import com.questjournal.core.state.Result;
import com.questjournal.core.validate.QuestValidator;
import com.questjournal.core.validate.RegistryChecker;

/** Builders shared by the tests. */
final class TestData {
    private TestData() {
    }

    static QuestDefinition quest(String id, AcceptanceMode mode, String... prerequisites) {
        QuestDefinition q = new QuestDefinition();
        q.id = id;
        q.title = "Quest " + id;
        q.acceptance = mode;
        q.prerequisites.addAll(List.of(prerequisites));
        return q;
    }

    static ObjectiveDefinition obj(QuestDefinition q, String id, ObjectiveType type, String target, int count) {
        ObjectiveDefinition o = new ObjectiveDefinition();
        o.id = id;
        o.type = type;
        o.target = target;
        o.count = count;
        q.objectives.add(o);
        return o;
    }

    static RewardDefinition itemReward(QuestDefinition q, String id, String item, int count) {
        RewardDefinition r = new RewardDefinition();
        r.id = id;
        r.type = RewardType.ITEM;
        r.item = item;
        r.count = count;
        q.rewards.add(r);
        return r;
    }

    /** Registry checker with an explicit set of existing ids (any registry kind) and loaded namespaces. */
    static final class FakeRegistry implements RegistryChecker {
        final Set<String> present = new HashSet<>();
        final Set<String> namespaces = new HashSet<>(Set.of("minecraft"));

        FakeRegistry with(String... ids) {
            for (String id : ids) {
                present.add(id);
                namespaces.add(id.substring(0, id.indexOf(':')));
            }
            return this;
        }

        @Override
        public boolean exists(RegistryKind kind, String id) {
            return present.contains(id) || id.startsWith("minecraft:");
        }

        @Override
        public boolean namespaceLoaded(String ns) {
            return namespaces.contains(ns);
        }
    }

    static QuestRegistry registry(QuestDefinition... quests) {
        return registry(RegistryChecker.ACCEPT_ALL, quests);
    }

    static QuestRegistry registry(RegistryChecker chk, QuestDefinition... quests) {
        List<QuestDefinition> list = List.of(quests);
        return QuestRegistry.build(1, list, Map.of(), QuestValidator.validate(list, chk, null, null));
    }

    /** One player against one registry, with a clock the tests can advance. */
    static final class Env {
        QuestRegistry reg;
        ProgressEngine eng;
        final PlayerProgress p = new PlayerProgress(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        long now = 1000;
        Changes ch = new Changes();

        Env(QuestDefinition... quests) {
            use(registry(quests));
        }

        void use(QuestRegistry r) {
            reg = r;
            eng = new ProgressEngine(r);
            ch = new Changes();
        }

        void eval() {
            eng.evaluateAll(p, ++now, ch);
        }

        Result accept(String id) {
            return eng.accept(p, id, ++now, ch);
        }

        Result adv(String id, int idx, int n) {
            return eng.advance(p, reg.get(id), idx, n, ++now, ch);
        }

        QuestStatus status(String id) {
            return eng.statusOf(p, reg.get(id));
        }

        /** Grants every reward and finishes the claim, like the server does after delivering rewards. */
        Result claim(String id) {
            if (!eng.checkClaim(p, reg.get(id)).success()) {
                return eng.checkClaim(p, reg.get(id));
            }
            for (RewardDefinition r : eng.pendingRewards(p, reg.get(id))) {
                eng.markRewardGranted(p, id, r.id);
            }
            return eng.finishClaim(p, reg.get(id), ++now, ch);
        }
    }
}
