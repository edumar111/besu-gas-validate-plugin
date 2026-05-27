// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @title IMembershipRegistry — fuente de verdad del tier de cada cuenta.
/// @dev El orden y los valores de {Tier} deben coincidir EXACTAMENTE con
///      `com.lacnet.besu.gas.model.Tier` del plugin Java. Si reordenás o
///      agregás valores acá, replicalo en Tier.java o el plugin va a
///      decodificar tiers incorrectos.
interface IMembershipRegistry {
    /// @dev NONE=0, BASIC=1, STANDARD=2, PREMIUM=3, WHITELISTED=4.
    enum Tier {
        NONE,
        BASIC,
        STANDARD,
        PREMIUM,
        WHITELISTED
    }

    event TierAssigned(address indexed account, Tier tier);
    event TierRevoked(address indexed account);

    /// @notice Tier asignado a `account`. Devuelve NONE si nunca se asignó.
    function getTier(address account) external view returns (Tier);

    /// @notice Asigna un tier a una cuenta. Debe ser distinto de NONE.
    /// @dev Para revocar usar {removeMember}.
    function setTier(address account, Tier tier) external;

    /// @notice Asignación batch. Útil para onboarding masivo.
    function setTierBatch(address[] calldata accounts, Tier[] calldata tiers) external;

    /// @notice Revoca la membresía: el sender vuelve a tener tier NONE.
    function removeMember(address account) external;
}
