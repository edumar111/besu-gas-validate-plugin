// SPDX-License-Identifier: MIT
pragma solidity 0.8.20;

/// @notice Contrato cuyo deploy consume mas de 500_000 gas, usado para validar
///         que el plugin rechaza TXs de cuentas BASIC que exceden el limite por
///         bloque. El constructor hace 30 SSTOREs sobre slots frios
///         (22_100 gas cada uno) -> ~663K + overhead = ~700K gas total.
contract FatStorage {
    mapping(uint256 => uint256) private slots;

    constructor() {
        for (uint256 i = 0; i < 30; i++) {
            slots[i] = i + 1;
        }
    }

    function get(uint256 i) external view returns (uint256) {
        return slots[i];
    }
}
