package com.questjournal;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.questjournal.config.QJConfig;
import com.questjournal.network.Net;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/** Quest Journal main class: registers configuration and the network channel. Everything else is event driven. */
@Mod(QuestJournalMod.MOD_ID)
public final class QuestJournalMod {
    public static final String MOD_ID = "questjournal";
    public static final Logger LOGGER = LogUtils.getLogger();

    public QuestJournalMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, QJConfig.CLIENT_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, QJConfig.COMMON_SPEC);
        Net.init();
    }
}
