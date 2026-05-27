// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IMembershipRegistry} from "./IMembershipRegistry.sol";

/// @title MembershipRegistry — registro on-chain del tier de cada cuenta.
/// @notice El plugin de gas en Besu consulta este contrato vía eth_call
///         (`getTier(address)`) para decidir si una TX cabe en su cuota.
/// @dev Fase 1: sin expiración, sin multisig. Owner único gestiona altas/bajas.
contract MembershipRegistry is IMembershipRegistry, Ownable {
    mapping(address => Tier) private _tiers;

    error LengthMismatch();
    error UseRemoveToRevoke();

    constructor(address initialOwner) Ownable(initialOwner) {}

    /// @inheritdoc IMembershipRegistry
    function getTier(address account) external view override returns (Tier) {
        return _tiers[account];
    }

    /// @inheritdoc IMembershipRegistry
    function setTier(address account, Tier tier) external override onlyOwner {
        if (tier == Tier.NONE) revert UseRemoveToRevoke();
        _tiers[account] = tier;
        emit TierAssigned(account, tier);
    }

    /// @inheritdoc IMembershipRegistry
    function setTierBatch(address[] calldata accounts, Tier[] calldata tiers)
        external
        override
        onlyOwner
    {
        if (accounts.length != tiers.length) revert LengthMismatch();
        for (uint256 i = 0; i < accounts.length; i++) {
            if (tiers[i] == Tier.NONE) revert UseRemoveToRevoke();
            _tiers[accounts[i]] = tiers[i];
            emit TierAssigned(accounts[i], tiers[i]);
        }
    }

    /// @inheritdoc IMembershipRegistry
    function removeMember(address account) external override onlyOwner {
        delete _tiers[account];
        emit TierRevoked(account);
    }
}
