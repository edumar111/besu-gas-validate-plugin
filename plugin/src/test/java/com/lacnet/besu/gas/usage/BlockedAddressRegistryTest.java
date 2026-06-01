package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class BlockedAddressRegistryTest {

    private static final Address ACC = Address.fromHexString("0x00000000000000000000000000000000000000b0");
    private static final Address OTHER = Address.fromHexString("0x00000000000000000000000000000000000000c1");
    private static final long TTL = 300_000L; // 5 min

    @Test
    void unknownAddressIsNotBlocked() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        assertFalse(reg.isBlocked(ACC, 1000L));
    }

    @Test
    void blockedWithinTtl() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);
        assertTrue(reg.isBlocked(ACC, 1000L));
        assertTrue(reg.isBlocked(ACC, 1000L + TTL - 1));
    }

    @Test
    void expiresAtTtlBoundary() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);
        // until = 1000 + TTL es exclusivo: a ese instante ya no está bloqueada.
        assertFalse(reg.isBlocked(ACC, 1000L + TTL));
        assertFalse(reg.isBlocked(ACC, 1000L + TTL + 5));
    }

    @Test
    void blockingIsPerAddress() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);
        assertFalse(reg.isBlocked(OTHER, 1000L));
    }

    @Test
    void reblockExtendsButNeverShortens() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);          // until = 301000
        reg.block(ACC, 1100L, 1L);           // until = 1101 < 301000 → no acorta
        assertTrue(reg.isBlocked(ACC, 300_000L));
        reg.block(ACC, 300_500L, TTL);       // until = 600500 → extiende
        assertTrue(reg.isBlocked(ACC, 400_000L));
    }

    @Test
    void blockedUntilReturnsExpiry() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);
        assertEquals(Optional.of(1000L + TTL), reg.blockedUntil(ACC, 1000L));
        assertEquals(Optional.empty(), reg.blockedUntil(ACC, 1000L + TTL));
        assertEquals(Optional.empty(), reg.blockedUntil(OTHER, 1000L));
    }

    @Test
    void cleanupRemovesExpired() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        reg.block(ACC, 1000L, TTL);          // until 301000
        reg.block(OTHER, 1000L, 10L);        // until 1010
        reg.cleanup(2000L);                  // OTHER vencida, ACC vigente
        assertTrue(reg.isBlocked(ACC, 2000L));
        assertFalse(reg.isBlocked(OTHER, 2000L));
    }

    @Test
    void negativeTtlThrows() {
        BlockedAddressRegistry reg = new BlockedAddressRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.block(ACC, 1000L, -1L));
    }
}
