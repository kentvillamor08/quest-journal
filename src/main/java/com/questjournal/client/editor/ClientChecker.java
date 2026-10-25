package com.questjournal.client.editor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.validate.RegistryChecker;

import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Editor-side "does this exist here?" check, using this client's registries (same mods as the server by Forge's rules). */
final class ClientChecker implements RegistryChecker {
    @Override
    public boolean exists(RegistryKind kind, String id) {
        if (kind == RegistryKind.DIMENSION) {
            return EditorSession.dimensions.contains(id);
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
        return true; // providers live on the server; the server re-validates on publish
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
