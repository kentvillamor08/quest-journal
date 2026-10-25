package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.questjournal.core.security.AdminPolicy;
import com.questjournal.core.security.ApprovalStore;
import com.questjournal.core.security.CommandPolicy;

class SecurityTest {
    private static CommandPolicy policy() {
        return new CommandPolicy(true, List.of("give", "xp", "effect", "tellraw", "op", "execute"), false);
    }

    @Test
    void allowlistedCommandsPass() {
        assertTrue(policy().check("give {player} minecraft:apple 1").isEmpty());
        assertTrue(policy().check("/xp add @s 100 points").isEmpty());
        assertTrue(policy().check("tellraw {player} {\"text\":\"hi {player}\"}").isEmpty());
    }

    @Test
    void dangerousRootsAreDeniedEvenIfSomeoneAllowlistsThem() {
        assertFalse(policy().check("op {player}").isEmpty());
        assertFalse(policy().check("/minecraft:op {player}").isEmpty(), "namespace prefix cannot bypass the deny list");
        assertFalse(policy().check("execute as @s run give {player} minecraft:dirt").isEmpty());
        assertFalse(policy().check("stop").isEmpty());
        assertFalse(policy().check("forge:tps").isEmpty(), "namespaced roots are refused");
    }

    @Test
    void anythingNotOnTheAllowlistIsDenied() {
        assertFalse(policy().check("kill @s").isEmpty());
        assertFalse(policy().check("summon minecraft:wither").isEmpty());
    }

    @Test
    void selectorsOtherThanSelfAreBlockedByDefault() {
        assertFalse(policy().check("give @a minecraft:diamond 64").isEmpty());
        assertFalse(policy().check("give @e[type=zombie] minecraft:diamond").isEmpty());
        assertFalse(policy().check("give @p minecraft:diamond").isEmpty());
        assertTrue(policy().check("give @s minecraft:diamond").isEmpty());
        assertTrue(new CommandPolicy(true, List.of("give"), true).check("give @a minecraft:diamond").isEmpty());
    }

    @Test
    void rejectsControlCharactersLengthAndUnknownPlaceholders() {
        assertFalse(policy().check("give {player} minecraft:apple\nop {player}").isEmpty());
        assertFalse(policy().check("give {player} " + "x".repeat(300)).isEmpty());
        assertFalse(policy().check("give {nickname} minecraft:apple").isEmpty());
        assertFalse(policy().check("").isEmpty());
        assertFalse(policy().check(null).isEmpty());
    }

    @Test
    void substitutionOnlyAcceptsValidatedValues() {
        CommandPolicy p = policy();
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals("give Steve_1 minecraft:apple 1", p.substitute("give {player} minecraft:apple 1", "Steve_1", uuid, "a:b"));
        assertEquals("tellraw 123e4567-e89b-12d3-a456-426614174000 q=a:b/c",
                p.substitute("tellraw {uuid} q={quest}", "Steve", uuid, "a:b/c"));
        for (String evil : new String[]{"Steve; op Steve", "Steve op", "Steve\nop Steve", "@a", "", "ThisNameIsWayTooLongForMinecraft", "Ste\"ve"}) {
            assertThrows(IllegalArgumentException.class, () -> p.substitute("give {player} minecraft:apple 1", evil, uuid, "a:b"));
        }
        assertThrows(IllegalArgumentException.class, () -> p.substitute("x {uuid}", "Steve", "not-a-uuid", "a:b"));
        assertThrows(IllegalArgumentException.class, () -> p.substitute("x {quest}", "Steve", uuid, "a:b run op"));
        assertThrows(IllegalArgumentException.class, () -> p.substitute("x {other}", "Steve", uuid, "a:b"));
    }

    @Test
    void fingerprintsChangeWhenTheCommandOrQuestChanges() {
        String f = CommandPolicy.fingerprint("a:b", "r1", "give {player} minecraft:apple 1");
        assertEquals(f, CommandPolicy.fingerprint("a:b", "r1", "  give {player} minecraft:apple 1 "));
        assertFalse(f.equals(CommandPolicy.fingerprint("a:b", "r1", "give {player} minecraft:apple 64")));
        assertFalse(f.equals(CommandPolicy.fingerprint("a:c", "r1", "give {player} minecraft:apple 1")));
        assertEquals(64, f.length());
    }

    @Test
    void adminPolicyTruthTable() {
        assertTrue(AdminPolicy.isAuthorized(true, false, false));
        assertTrue(AdminPolicy.isAuthorized(true, true, true));
        assertFalse(AdminPolicy.isAuthorized(false, false, true), "ordinary players are never administrators");
        assertFalse(AdminPolicy.isAuthorized(false, false, false));
        assertTrue(AdminPolicy.isAuthorized(false, true, true), "single-player owner without cheats");
        assertFalse(AdminPolicy.isAuthorized(false, true, false), "unless the server config turns that off");
    }

    @Test
    void approvalsPersistAndCanBeRevoked(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cfg/approved_commands.json");
        ApprovalStore s = new ApprovalStore(file);
        s.load();
        assertFalse(s.isApproved("abc"));
        s.approve("abc", "a:b", "r1", "Admin", 5L);
        assertTrue(s.isApproved("abc"));

        ApprovalStore reopened = new ApprovalStore(file);
        reopened.load();
        assertTrue(reopened.isApproved("abc"));
        assertEquals(1, reopened.size());
        assertTrue(reopened.revoke("abc"));
        assertFalse(reopened.revoke("abc"));
        ApprovalStore third = new ApprovalStore(file);
        third.load();
        assertFalse(third.isApproved("abc"));
    }
}
