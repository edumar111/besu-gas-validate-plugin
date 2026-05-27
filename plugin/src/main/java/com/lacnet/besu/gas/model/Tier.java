package com.lacnet.besu.gas.model;

/**
 * Tier de membresía. El orden y los valores numéricos deben coincidir EXACTAMENTE con el enum
 * Solidity {@code enum Tier { NONE, BASIC, STANDARD, PREMIUM, WHITELISTED }} del contrato
 * {@code MembershipRegistry} — Solidity asigna 0..4 a las constantes en orden de declaración, y
 * eso es lo que vuelve serializado en {@code getTier(address)}.
 *
 * <p>Si en algún momento se reordena/agrega un valor en Solidity, el cambio debe replicarse acá
 * y viceversa, o el plugin va a decodificar tiers incorrectos.
 */
public enum Tier {
    NONE(0),
    BASIC(1),
    STANDARD(2),
    PREMIUM(3),
    WHITELISTED(4);

    private final int onChainValue;

    Tier(final int onChainValue) {
        this.onChainValue = onChainValue;
    }

    public int onChainValue() {
        return onChainValue;
    }

    public static Tier fromOnChainValue(final int value) {
        for (Tier tier : values()) {
            if (tier.onChainValue == value) {
                return tier;
            }
        }
        throw new IllegalArgumentException(
                "Unknown Tier on-chain value: " + value
                        + " (expected 0..4 matching Solidity enum order)");
    }
}
