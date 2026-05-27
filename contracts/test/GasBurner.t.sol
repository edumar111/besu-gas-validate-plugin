// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Test, console2} from "forge-std/Test.sol";
import {GasBurner} from "../src/GasBurner.sol";

/// @dev Verifica que `consumeGas(target)` cae cerca del target en los rangos relevantes
///      para los tests E2E (BASIC=500K, STANDARD=5M, PREMIUM=10M). Tolerancia ±10% — la
///      mecánica de SSTORE warm es estable pero no exacta.
contract GasBurnerTest is Test {
    GasBurner private burner;

    function setUp() public {
        burner = new GasBurner();
    }

    function _assertCloseToTarget(uint256 actual, uint256 target, uint256 tolPct) internal pure {
        uint256 lo = target - (target * tolPct) / 100;
        uint256 hi = target + (target * tolPct) / 100;
        require(actual >= lo && actual <= hi,
            string.concat(
                "actual=", vm.toString(actual),
                " out of bounds [", vm.toString(lo), ", ", vm.toString(hi), "]"
            )
        );
    }

    function test_PrecisionEnTargetsRelevantes() public {
        // Los targets que usan los tests E2E.
        uint256[6] memory targets = [
            uint256(100_000),     // smoke
            uint256(200_000),     // saturación BASIC (5 × 200K → 500K)
            uint256(500_000),     // BASIC entero
            uint256(4_000_000),   // STANDARD parcial
            uint256(5_000_000),   // STANDARD entero
            uint256(10_000_000)   // PREMIUM entero
        ];

        for (uint256 i = 0; i < targets.length; i++) {
            uint256 target = targets[i];
            uint256 actual = burner.consumeGas(target);
            console2.log("target", target, "actual", actual);
            _assertCloseToTarget(actual, target, 10);
        }
    }

    function test_TargetMuyChicoNoLanza() public {
        // Edge case: target < costo de una iteración → loop no entra, retorna ~50 gas.
        uint256 actual = burner.consumeGas(100);
        // Solo verificamos que no revierte y devuelve algo pequeño.
        require(actual < 10_000, "target chico no deberia consumir mucho");
    }

    function test_TargetCeroNoLanza() public {
        uint256 actual = burner.consumeGas(0);
        require(actual < 10_000, "target=0 retorna overhead minimo");
    }

    function test_ConsumoCreceConTarget() public {
        // Mostrar que consumeGas es monótono creciente en target.
        uint256 a = burner.consumeGas(100_000);
        uint256 b = burner.consumeGas(500_000);
        uint256 c = burner.consumeGas(1_000_000);
        require(a < b && b < c, "consumo crece con target");
    }
}
