package com.questjournal.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/** The journal key binding. It appears in Options > Controls under "Quest Journal" and can be rebound freely. */
public final class Keys {
    public static final KeyMapping OPEN_JOURNAL = new KeyMapping("key.questjournal.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.questjournal");

    private Keys() {
    }
}
