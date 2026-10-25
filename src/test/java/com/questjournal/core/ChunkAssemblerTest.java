package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.questjournal.core.util.ChunkAssembler;

class ChunkAssemblerTest {
    @Test
    void reassemblesInOrderAndEnforcesLimits() {
        ChunkAssembler a = new ChunkAssembler(10);
        assertNull(a.accept(1, 0, 2, new byte[]{1, 2, 3}));
        assertEquals(6, a.accept(1, 1, 2, new byte[]{4, 5, 6}).length);
        assertEquals(1, a.accept(2, 0, 1, new byte[]{9}).length, "single chunk payloads complete immediately");

        ChunkAssembler b = new ChunkAssembler(4);
        b.accept(1, 0, 2, new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> b.accept(1, 1, 2, new byte[]{4, 5}));

        ChunkAssembler c = new ChunkAssembler(100);
        c.accept(1, 0, 3, new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> c.accept(1, 2, 3, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> c.accept(1, 0, 0, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> c.accept(1, 5, 3, new byte[]{1}));
    }
}
