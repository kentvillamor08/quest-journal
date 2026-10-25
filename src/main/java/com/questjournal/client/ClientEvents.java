package com.questjournal.client;

import com.questjournal.QuestJournalMod;
import com.questjournal.client.ui.JournalScreen;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only event wiring (never loaded on a dedicated server). */
public final class ClientEvents {
    private ClientEvents() {
    }

    @Mod.EventBusSubscriber(modid = QuestJournalMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(Keys.OPEN_JOURNAL);
        }
    }

    @Mod.EventBusSubscriber(modid = QuestJournalMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            while (Keys.OPEN_JOURNAL.consumeClick()) {
                if (mc.player != null && mc.screen == null) {
                    mc.setScreen(new JournalScreen(null));
                }
            }
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientJournal.I.clear();
            com.questjournal.client.editor.EditorSession.clear();
        }
    }
}
