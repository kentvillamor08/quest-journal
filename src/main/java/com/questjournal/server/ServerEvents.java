package com.questjournal.server;

import com.questjournal.QuestJournalMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge event wiring for the server side. Each handler returns immediately unless the player has a matching active
 * objective (checked in QuestService with a single index lookup), so ordinary gameplay and big fights cost nothing extra.
 */
@Mod.EventBusSubscriber(modid = QuestJournalMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    private ServerEvents() {
    }

    private static ServerPlayer real(Entity e) {
        return e instanceof ServerPlayer && !(e instanceof FakePlayer) ? (ServerPlayer) e : null;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerAboutToStartEvent event) {
        QuestService.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        QuestService.stop();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        QJCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onLogin(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onLogout(p);
        }
    }

    /** Lowest priority and not receiving cancelled events: deaths prevented by totems/other mods are never counted. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        QuestService svc = QuestService.get();
        if (svc == null || event.getEntity().level().isClientSide) {
            return;
        }
        LivingEntity victim = event.getEntity();
        ServerPlayer killer = real(event.getSource().getEntity());
        if (killer == null) {
            killer = real(victim.getKillCredit()); // e.g. a mob knocked into lava by the player
        }
        if (killer != null && killer != victim) {
            svc.onKill(killer, victim);
        }
    }

    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onItemGained(p, event.getStack());
        }
    }

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onItemGained(p, event.getCrafting());
        }
    }

    @SubscribeEvent
    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onItemGained(p, event.getSmelting());
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onContainerClosed(p);
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        ServerPlayer p = real(event.getEntity());
        if (p != null && QuestService.get() != null) {
            QuestService.get().onDimensionChanged(p, event.getTo());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        ServerPlayer p = real(event.getPlayer());
        if (p != null && QuestService.get() != null && !event.isCanceled()) {
            QuestService.get().onBlockBroken(p, event.getState());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer p = real(event.getEntity());
        if (p == null || QuestService.get() == null || event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND
                || event.getUseBlock() == Event.Result.DENY || p.isSpectator()) {
            return;
        }
        QuestService.get().onBlockInteracted(p, p.level().getBlockState(event.getPos()), event.getPos());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && QuestService.get() != null) {
            QuestService.get().tick(QuestService.get().server.getTickCount());
        }
    }
}
