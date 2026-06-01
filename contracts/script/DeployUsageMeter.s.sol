// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Script, console2} from "forge-std/Script.sol";
import {UsageMeter} from "../src/UsageMeter.sol";

/// @title Deploy UsageMeter (Fase 2) a la red local de Besu (chainId 650540, RPC :4545).
/// @dev Uso:
///   OWNER=0x... RECORDER=0x... forge script script/DeployUsageMeter.s.sol \
///     --rpc-url local \
///     --private-key $PRIVATE_KEY \
///     --broadcast --legacy
///
/// El flag --legacy fuerza TX tipo 0 (gasPrice=0) — la red usa min-gas-price=0.
///
/// Variables de entorno:
///   OWNER    (address) — initial owner del UsageMeter. Default: msg.sender (broadcaster).
///   RECORDER (address) — cuenta autorizada a commitear uso (el nodo recorder del plugin).
///                        OBLIGATORIA. Debe coincidir con la dirección derivada de
///                        ratelimit.usage.recorderKey y estar WHITELISTED en el MembershipRegistry.
contract DeployUsageMeterScript is Script {
    function run() external returns (UsageMeter meter) {
        address initialOwner = vm.envOr("OWNER", msg.sender);
        address recorder = vm.envAddress("RECORDER");

        vm.startBroadcast();
        meter = new UsageMeter(initialOwner, recorder);
        vm.stopBroadcast();

        console2.log("UsageMeter deployed at:", address(meter));
        console2.log("Owner:", meter.owner());
        console2.log("Recorder:", meter.recorder());
    }
}
