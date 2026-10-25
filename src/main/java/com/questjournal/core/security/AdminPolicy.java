package com.questjournal.core.security;

/**
 * Decides who may use administrative features (editor, reload, import/export, approvals).
 * The Forge layer computes the inputs from the real player; every admin packet and command goes through here.
 */
public final class AdminPolicy {
    private AdminPolicy() {
    }

    /**
     * @param hasRequiredOpLevel      the player has at least the configured operator permission level
     * @param isSingleplayerOwner     the player owns the integrated (single-player) world
     * @param singleplayerOwnerIsAdmin config switch: treat the owner of a single-player world as an administrator
     */
    public static boolean isAuthorized(boolean hasRequiredOpLevel, boolean isSingleplayerOwner,
                                       boolean singleplayerOwnerIsAdmin) {
        return hasRequiredOpLevel || (singleplayerOwnerIsAdmin && isSingleplayerOwner);
    }
}
