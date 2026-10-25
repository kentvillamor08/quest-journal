package com.questjournal.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public API for optional compatibility integrations. */
public final class QuestJournalApi {
    private static final Map<String, RewardProvider> PROVIDERS = new ConcurrentHashMap<>();

    private QuestJournalApi() {
    }

    public static void registerRewardProvider(String id, RewardProvider provider) {
        PROVIDERS.put(id, provider);
    }

    public static RewardProvider rewardProvider(String id) {
        return PROVIDERS.get(id);
    }
}
