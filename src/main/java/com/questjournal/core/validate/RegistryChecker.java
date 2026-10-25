package com.questjournal.core.validate;

import com.questjournal.core.model.RegistryKind;

/**
 * Abstraction over "does this registry entry exist right now?". The Forge implementation queries
 * ForgeRegistries / the server's dimensions; unit tests supply a fake. Keeping this an interface is what lets
 * the whole validation pipeline run (and be tested) without Minecraft.
 */
public interface RegistryChecker {
    boolean exists(RegistryKind kind, String id);

    /** Is a mod with this namespace installed? Used only to produce clearer messages. */
    default boolean namespaceLoaded(String namespace) {
        return true;
    }

    /** Is a custom reward provider with this id registered? */
    default boolean rewardProviderExists(String providerId) {
        return false;
    }

    /** Returns an error message if the SNBT string cannot be parsed, or null when it is fine. */
    default String validateNbt(String snbt) {
        return null;
    }

    /** Accepts everything. Useful for tools that only care about structure. */
    RegistryChecker ACCEPT_ALL = new RegistryChecker() {
        @Override
        public boolean exists(RegistryKind kind, String id) {
            return true;
        }

        @Override
        public boolean rewardProviderExists(String providerId) {
            return true;
        }
    };
}
