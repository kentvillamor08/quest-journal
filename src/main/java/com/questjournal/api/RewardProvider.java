package com.questjournal.api;

import java.util.Map;

import net.minecraft.server.level.ServerPlayer;

/**
 * Extension point for optional integrations (no hard dependency needed): another mod registers a provider with
 * {@link QuestJournalApi#registerRewardProvider} and quests reference it with {"type":"custom","provider":"yourmod:id"}.
 */
@FunctionalInterface
public interface RewardProvider {
    /** Grants the reward. Return null on success or a player-readable error message (the claim is then retried later). */
    String grant(ServerPlayer player, String questId, Map<String, Object> data);
}
