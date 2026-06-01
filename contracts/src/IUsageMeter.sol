// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @title IUsageMeter — libro contable on-chain del consumo de gas mensual por cuenta.
/// @notice Fase 2 del sistema de membresías de gas. El plugin de Besu mantiene un contador
///         in-memory para el enforcement en caliente y, desde un único nodo "recorder",
///         commitea periódicamente el delta acumulado a este contrato (persistencia + auditoría
///         para billing). El plugin lee `getUsage` vía eth_call solo para rehidratar su baseline
///         tras un restart.
/// @dev Las CUOTAS mensuales NO viven acá — viven en la config del plugin (igual que las quotas
///      per-block de Fase 1). Este contrato solo almacena el consumo; el enforcement compara
///      contra la cuota del plugin. Esto mantiene una sola fuente de verdad para las cuotas.
interface IUsageMeter {
    /// @notice Emitido por cada cuenta actualizada en un batch.
    /// @param periodId Período (mes calendario UTC = year*12 + month0) al que aplica el uso.
    /// @param account Cuenta cuyo consumo se actualizó.
    /// @param newTotal Consumo acumulado de la cuenta en ese período tras sumar el delta.
    event UsageRecorded(uint256 indexed periodId, address indexed account, uint256 newTotal);

    /// @notice Emitido cuando el owner cambia la cuenta autorizada a commitear uso.
    event RecorderUpdated(address indexed recorder);

    /// @notice Consumo acumulado de `account` en `periodId`.
    function getUsage(uint256 periodId, address account) external view returns (uint256);

    /// @notice Suma `gasDeltas[i]` al consumo de `accounts[i]` en `periodId`. Solo el recorder.
    /// @dev Acumulativo: el recorder envía solo el delta no commiteado desde el último flush.
    function recordUsageBatch(
        uint256 periodId,
        address[] calldata accounts,
        uint256[] calldata gasDeltas
    ) external;

    /// @notice Cuenta autorizada a llamar `recordUsageBatch` (el nodo recorder). Solo el owner.
    function setRecorder(address recorder) external;

    /// @notice Dirección actual del recorder.
    function recorder() external view returns (address);
}
