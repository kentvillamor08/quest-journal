package com.questjournal.server;

import com.questjournal.config.QJConfig;
import com.questjournal.core.security.AdminPolicy;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Server-side permission checks. Every administrative packet and command goes through here; hiding a button is never the only guard. */
public final class Admin {
    private Admin() {
    }

    public static boolean isAdmin(ServerPlayer player) {
        boolean level = player.hasPermissions(QJConfig.COMMON.editorPermissionLevel.get());
        boolean owner = player.getServer() != null && player.getServer().isSingleplayerOwner(player.getGameProfile());
        return AdminPolicy.isAuthorized(level, owner, QJConfig.COMMON.singleplayerOwnerIsAdmin.get());
    }

    public static boolean isAdmin(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            return isAdmin((ServerPlayer) entity);
        }
        return source.hasPermission(QJConfig.COMMON.editorPermissionLevel.get()); // console, command blocks
    }
}
