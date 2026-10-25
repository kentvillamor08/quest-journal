package com.questjournal.server;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.questjournal.QuestJournalMod;
import com.questjournal.api.QuestJournalApi;
import com.questjournal.api.RewardProvider;
import com.questjournal.config.QJConfig;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RewardDefinition;
import com.questjournal.core.model.XpUnit;
import com.questjournal.core.security.CommandPolicy;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Reward delivery. {@link #precheck} verifies EVERYTHING before anything is given, so a claim is all-or-nothing in the
 * normal case (unresolved item, full inventory, disabled/unapproved command). {@link #grant} then delivers one reward;
 * the caller records each success so a retry never repeats a reward.
 */
final class Rewards {
    private Rewards() {
    }

    /** Returns a player-readable reason the pending rewards cannot be delivered right now, or null if all can. */
    static String precheck(QuestService svc, ServerPlayer p, QuestDefinition q, List<RewardDefinition> pending) {
        CommandPolicy policy = QJConfig.commandPolicy();
        List<ItemStack> stacks = new ArrayList<>();
        for (RewardDefinition r : pending) {
            switch (r.type) {
                case ITEM: {
                    ItemStack s = makeStack(r);
                    if (s == null) {
                        return "The reward item '" + r.item + "' is not available on this server. Ask an administrator.";
                    }
                    stacks.addAll(split(s));
                    break;
                }
                case COMMAND: {
                    String why = commandProblem(svc, policy, q, r);
                    if (why != null) {
                        return why;
                    }
                    break;
                }
                case CUSTOM:
                    if (QuestJournalApi.rewardProvider(r.provider) == null) {
                        return "The reward provider '" + r.provider + "' is not available. Ask an administrator.";
                    }
                    break;
                default:
                    break;
            }
        }
        if (!fits(p.getInventory(), stacks)) {
            return "Not enough inventory space for the item rewards. Make room and claim again.";
        }
        return null;
    }

    private static String commandProblem(QuestService svc, CommandPolicy policy, QuestDefinition q, RewardDefinition r) {
        if (!policy.enabled()) {
            return "One reward runs a server command, and command rewards are disabled on this server. Ask an administrator.";
        }
        List<String> why = policy.check(r.command);
        if (!why.isEmpty()) {
            return "A reward command is blocked by the server's security policy (" + why.get(0) + ").";
        }
        if (!svc.approvals.isApproved(CommandPolicy.fingerprint(q.id, r.id, r.command))) {
            return "A reward command is waiting for administrator approval.";
        }
        return null;
    }

    /** Delivers one reward. Returns null on success, otherwise an error message (nothing is recorded as granted). */
    static String grant(QuestService svc, ServerPlayer p, QuestDefinition q, RewardDefinition r) {
        switch (r.type) {
            case ITEM: {
                ItemStack s = makeStack(r);
                if (s == null) {
                    return "The reward item '" + r.item + "' is not available.";
                }
                for (ItemStack part : split(s)) {
                    if (!p.getInventory().add(part) && !part.isEmpty()) {
                        // Cannot happen after precheck; drop at the player's feet rather than ever losing the item.
                        QuestJournalMod.LOGGER.warn("[QuestJournal] Inventory full while granting {} to {}; dropped it instead.", r.item, p.getName().getString());
                        p.drop(part, false);
                    }
                }
                return null;
            }
            case EXPERIENCE:
                if (r.unit == XpUnit.LEVELS) {
                    p.giveExperienceLevels(r.amount);
                } else {
                    p.giveExperiencePoints(r.amount);
                }
                return null;
            case COMMAND:
                return runCommand(svc, p, q, r);
            case CUSTOM: {
                RewardProvider provider = QuestJournalApi.rewardProvider(r.provider);
                if (provider == null) {
                    return "The reward provider '" + r.provider + "' is not available.";
                }
                try {
                    return provider.grant(p, q.id, r.data);
                } catch (RuntimeException e) {
                    QuestJournalMod.LOGGER.error("[QuestJournal] Reward provider {} threw for quest {}", r.provider, q.id, e);
                    return "The reward could not be delivered (see the server log).";
                }
            }
            default:
                return "Unknown reward type.";
        }
    }

    private static String runCommand(QuestService svc, ServerPlayer p, QuestDefinition q, RewardDefinition r) {
        CommandPolicy policy = QJConfig.commandPolicy();
        String why = commandProblem(svc, policy, q, r);
        if (why != null) {
            return why;
        }
        String cmd;
        try {
            cmd = policy.substitute(r.command, p.getGameProfile().getName(), p.getUUID().toString(), q.id);
        } catch (IllegalArgumentException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Refused command reward {}/{} for {}: {}", q.id, r.id, p.getName().getString(), e.getMessage());
            return "A reward command was refused: " + e.getMessage();
        }
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        CommandSourceStack source = p.createCommandSourceStack()
                .withPermission(QJConfig.COMMON.commandPermissionLevel.get()).withSuppressedOutput();
        try {
            int result = svc.server.getCommands().getDispatcher().execute(cmd, source);
            QuestJournalMod.LOGGER.info("[QuestJournal] Command reward {}/{} for {} ran '/{}' (result {})",
                    q.id, r.id, p.getName().getString(), cmd, result);
            return null;
        } catch (CommandSyntaxException e) {
            QuestJournalMod.LOGGER.warn("[QuestJournal] Command reward {}/{} failed: {}", q.id, r.id, e.getMessage());
            return "A reward command failed: " + e.getMessage();
        } catch (RuntimeException e) {
            QuestJournalMod.LOGGER.error("[QuestJournal] Command reward {}/{} threw", q.id, r.id, e);
            return "A reward command failed (see the server log).";
        }
    }

    // ------------------------------------------------------------------ item helpers

    static ItemStack makeStack(RewardDefinition r) {
        ResourceLocation rl = ResourceLocation.tryParse(r.item);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        ItemStack stack = new ItemStack(item, r.count);
        if (!r.nbt.isEmpty()) {
            try {
                stack.setTag(TagParser.parseTag(r.nbt));
            } catch (CommandSyntaxException e) {
                return null;
            }
        }
        return stack;
    }

    private static List<ItemStack> split(ItemStack s) {
        List<ItemStack> out = new ArrayList<>();
        int left = s.getCount();
        int max = Math.max(1, s.getMaxStackSize());
        while (left > 0) {
            ItemStack part = s.copy();
            int n = Math.min(max, left);
            part.setCount(n);
            out.add(part);
            left -= n;
        }
        return out;
    }

    /** Simulates inserting all stacks into a copy of the inventory (36 main slots + off hand for merging only). */
    private static boolean fits(Inventory inv, List<ItemStack> stacks) {
        ItemStack[] slots = new ItemStack[37];
        for (int i = 0; i < 36; i++) {
            slots[i] = inv.getItem(i).copy();
        }
        slots[36] = inv.getItem(40).copy();
        for (ItemStack s : stacks) {
            int left = s.getCount();
            for (int i = 0; i < slots.length && left > 0; i++) {
                ItemStack t = slots[i];
                if (!t.isEmpty() && ItemStack.isSameItemSameTags(t, s) && t.getCount() < t.getMaxStackSize()) {
                    int move = Math.min(left, t.getMaxStackSize() - t.getCount());
                    t.grow(move);
                    left -= move;
                }
            }
            for (int i = 0; i < 36 && left > 0; i++) {
                if (slots[i].isEmpty()) {
                    ItemStack placed = s.copy();
                    int move = Math.min(left, placed.getMaxStackSize());
                    placed.setCount(move);
                    slots[i] = placed;
                    left -= move;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }
}
