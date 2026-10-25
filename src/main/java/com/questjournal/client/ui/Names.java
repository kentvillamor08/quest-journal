package com.questjournal.client.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.questjournal.core.model.ObjectiveDefinition;
import com.questjournal.core.model.RegistryKind;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/** Readable names for registry ids (any mod); unresolved ids are shown as the raw id. */
public final class Names {
    private static final Map<String, String> CACHE = new HashMap<>();

    private Names() {
    }

    public static void clear() {
        CACHE.clear();
    }

    public static String name(RegistryKind kind, String id) {
        return CACHE.computeIfAbsent(kind.name() + "|" + id, k -> compute(kind, id));
    }

    private static String compute(RegistryKind kind, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return id;
        }
        switch (kind) {
            case ITEM:
                if (ForgeRegistries.ITEMS.containsKey(rl)) {
                    return ForgeRegistries.ITEMS.getValue(rl).getDescription().getString();
                }
                return id;
            case ENTITY:
                if (ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                    return ForgeRegistries.ENTITY_TYPES.getValue(rl).getDescription().getString();
                }
                return id;
            case BLOCK:
                if (ForgeRegistries.BLOCKS.containsKey(rl)) {
                    return ForgeRegistries.BLOCKS.getValue(rl).getName().getString();
                }
                return id;
            default: // dimensions have no translation key: prettify the path
                StringBuilder sb = new StringBuilder();
                for (String part : rl.getPath().replace('/', ' ').split("_| ")) {
                    if (!part.isEmpty()) {
                        sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT)).append(' ');
                    }
                }
                return sb.toString().trim();
        }
    }

    /** The author's description if there is one, otherwise a readable sentence built from display names. */
    public static String describe(ObjectiveDefinition o) {
        if (!o.description.isEmpty()) {
            return o.description;
        }
        String n = name(o.type.targetKind, o.target);
        switch (o.type) {
            case KILL_ENTITY: return "Defeat " + o.count + "x " + n;
            case OBTAIN_ITEM: return "Obtain " + o.count + "x " + n;
            case HAVE_ITEM: return "Have " + o.count + "x " + n + " in your inventory";
            case DELIVER_ITEM: return "Deliver " + o.count + "x " + n;
            case VISIT_DIMENSION: return "Travel to " + n;
            case VISIT_LOCATION: return "Reach " + (int) o.x + ", " + (int) o.y + ", " + (int) o.z + " in " + n;
            case BREAK_BLOCK: return "Break " + o.count + "x " + n;
            case INTERACT_BLOCK: return "Interact with " + n + (o.count > 1 ? " (" + o.count + "x)" : "");
            default: return o.autoDescription();
        }
    }
}
