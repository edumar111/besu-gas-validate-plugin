package com.lacnet.besu.gas.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TierTest {

    @Test
    void onChainValuesMatchSolidityEnumOrder() {
        // Debe coincidir con: enum Tier { NONE, BASIC, STANDARD, PREMIUM, WHITELISTED }
        // en MembershipRegistry.sol — si esto falla, el plugin decodifica tiers incorrectos.
        assertEquals(0, Tier.NONE.onChainValue());
        assertEquals(1, Tier.BASIC.onChainValue());
        assertEquals(2, Tier.STANDARD.onChainValue());
        assertEquals(3, Tier.PREMIUM.onChainValue());
        assertEquals(4, Tier.WHITELISTED.onChainValue());
    }

    @Test
    void fromOnChainValueDecodesAllValidValues() {
        assertEquals(Tier.NONE, Tier.fromOnChainValue(0));
        assertEquals(Tier.BASIC, Tier.fromOnChainValue(1));
        assertEquals(Tier.STANDARD, Tier.fromOnChainValue(2));
        assertEquals(Tier.PREMIUM, Tier.fromOnChainValue(3));
        assertEquals(Tier.WHITELISTED, Tier.fromOnChainValue(4));
    }

    @Test
    void fromOnChainValueRejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> Tier.fromOnChainValue(-1));
        assertThrows(IllegalArgumentException.class, () -> Tier.fromOnChainValue(5));
        assertThrows(IllegalArgumentException.class, () -> Tier.fromOnChainValue(255));
    }
}
