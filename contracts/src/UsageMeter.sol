// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IUsageMeter} from "./IUsageMeter.sol";

/// @title UsageMeter — consumo de gas acumulado por cuenta y período (Fase 2).
/// @notice Lo escribe el nodo recorder del plugin de gas vía `recordUsageBatch` (commit batch
///         cada N bloques). Lo lee el plugin vía `getUsage` (eth_call) para rehidratar su
///         contador in-memory tras un restart, y servicios externos para reconciliación de billing.
/// @dev Owner gestiona el rol recorder. Sin expiración: el aislamiento por período lo da `periodId`.
contract UsageMeter is IUsageMeter, Ownable {
    /// periodId => account => gas acumulado en ese período.
    mapping(uint256 => mapping(address => uint256)) private _usage;

    address private _recorder;

    error LengthMismatch();
    error NotRecorder();
    error ZeroRecorder();

    modifier onlyRecorder() {
        if (msg.sender != _recorder) revert NotRecorder();
        _;
    }

    constructor(address initialOwner, address initialRecorder) Ownable(initialOwner) {
        if (initialRecorder == address(0)) revert ZeroRecorder();
        _recorder = initialRecorder;
        emit RecorderUpdated(initialRecorder);
    }

    /// @inheritdoc IUsageMeter
    function getUsage(uint256 periodId, address account) external view override returns (uint256) {
        return _usage[periodId][account];
    }

    /// @inheritdoc IUsageMeter
    function recordUsageBatch(
        uint256 periodId,
        address[] calldata accounts,
        uint256[] calldata gasDeltas
    ) external override onlyRecorder {
        if (accounts.length != gasDeltas.length) revert LengthMismatch();
        for (uint256 i = 0; i < accounts.length; i++) {
            uint256 newTotal = _usage[periodId][accounts[i]] + gasDeltas[i];
            _usage[periodId][accounts[i]] = newTotal;
            emit UsageRecorded(periodId, accounts[i], newTotal);
        }
    }

    /// @inheritdoc IUsageMeter
    function setRecorder(address recorder_) external override onlyOwner {
        if (recorder_ == address(0)) revert ZeroRecorder();
        _recorder = recorder_;
        emit RecorderUpdated(recorder_);
    }

    /// @inheritdoc IUsageMeter
    function recorder() external view override returns (address) {
        return _recorder;
    }
}
