// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @title GasBurner — contrato auxiliar para tests E2E del plugin de gas.
/// @notice {consumeGas} gasta aproximadamente `target` gas y devuelve el consumo real.
/// @dev Cada iteración del loop hace una SSTORE sobre un slot "warm" (precalentado en el
///      constructor con valor distinto de 0) — costo ~5000 gas. Para targets ≥ 50K la
///      precisión es ±5%; para targets < 50K el overhead relativo del prólogo/epílogo se
///      hace notar pero el contrato sigue funcionando.
contract GasBurner {
    /// @dev Anillo de 16 slots precalentados. SSTORE sobre un slot warm con valor existente
    ///      (no-cero) cuesta 5000 gas — más predecible que sobre slots cold (22100) o desde 0.
    uint256 private constant SLOTS = 16;
    mapping(uint256 => uint256) private _slots;

    /// @notice Cuánto gas (aproximadamente) cuesta una iteración del loop interno.
    /// @dev Útil para que el caller estime cuántas iteraciones se ejecutarán.
    uint256 public constant GAS_PER_ITERATION = 5_000;

    constructor() {
        // Pre-warm: dejar todos los slots con un valor no-cero para que las SSTORE
        // posteriores caigan en la rama warm-non-zero (5000 gas).
        for (uint256 i = 0; i < SLOTS; i++) {
            _slots[i] = 1;
        }
    }

    /// @notice Quema aproximadamente `target` gas mediante SSTOREs y devuelve el gas
    ///         real consumido por la función (sin contar el 21K base de la TX).
    /// @param target Gas objetivo. Si es muy chico (< ~6K), no entra al loop y devuelve
    ///               el overhead mínimo (~50 gas).
    /// @return actual Gas consumido por la función, medido entre el primer y último
    ///         `gasleft()`.
    function consumeGas(uint256 target) external returns (uint256 actual) {
        uint256 startGas = gasleft();
        uint256 i = 0;

        // Salimos cuando ya consumimos `target` o quedaría poco margen para una iteración más.
        while (startGas - gasleft() + GAS_PER_ITERATION + 500 < target) {
            // SSTORE warm-non-zero (~5000 gas) + SLOAD + ADD + control flow ≈ 5_150 gas/it
            _slots[i] = _slots[i] + 1;
            unchecked {
                i = (i + 1) % SLOTS;
            }
        }

        actual = startGas - gasleft();
    }

    /// @notice Versión view para introspección — devuelve el contador del slot i sin gastar SSTORE.
    function slotValue(uint256 i) external view returns (uint256) {
        return _slots[i % SLOTS];
    }
}
