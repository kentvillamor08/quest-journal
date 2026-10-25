package com.questjournal.server;

import java.util.HashSet;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.questjournal.api.QuestJournalApi;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.validate.RegistryChecker;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Resolves quest references against the live Forge registries; works for any mod without per-mod code. */
public final class ForgeRegistryChecker implements RegistryChecker {
    private final Set<String> dimensions = new HashSet<>();

    public ForgeRegistryChecker(MinecraftServer server) {
        for (ResourceKey<Level> key : server.levelKeys()) {
            dimensions.add(key.location().toString());
        }
        for (ResourceLocation id : server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet()) {
            dimensions.add(id.toString());
        }
    }

    public Set<String> dimensionIds() {
        return dimensions;
    }

    @Override
    public boolean exists(RegistryKind kind, String id) {
        if (kind == RegistryKind.DIMENSION) {
            return dimensions.contains(id);
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return false;
        }
        switch (kind) {
            case ITEM: return ForgeRegistries.ITEMS.containsKey(rl);
            case ENTITY: return ForgeRegistries.ENTITY_TYPES.containsKey(rl);
            case BLOCK: return ForgeRegistries.BLOCKS.containsKey(rl);
            default: return false;
        }
    }

    @Override
    public boolean namespaceLoaded(String namespace) {
        return namespace.equals("minecraft") || ModList.get().isLoaded(namespace);
    }

    @Override
    public boolean rewardProviderExists(String providerId) {
        return QuestJournalApi.rewardProvider(providerId) != null;
    }

    @Override
    public String validateNbt(String snbt) {
        try {
            TagParser.parseTag(snbt);
            return null;
        } catch (CommandSyntaxException e) {
            return e.getMessage();
        }
    }
}
