// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Script, console2} from "forge-std/Script.sol";
import {MembershipRegistry} from "../src/MembershipRegistry.sol";

/// @title Deploy MembershipRegistry a la red local de Besu (chainId 650540, RPC :4545).
/// @dev Uso:
///   forge script script/Deploy.s.sol \
///     --rpc-url local \
///     --private-key $PRIVATE_KEY \
///     --broadcast --legacy
///
/// El flag --legacy fuerza TX tipo 0 (gasPrice=0, sin EIP-1559) — necesario porque
/// la red usa min-gas-price=0 y baseFeePerGas=0.
///
/// Variable de entorno opcional:
///   OWNER (address) — initial owner del contrato. Default: msg.sender (broadcaster).
contract DeployScript is Script {
    function run() external returns (MembershipRegistry registry) {
        address initialOwner = vm.envOr("OWNER", msg.sender);

        vm.startBroadcast();
        registry = new MembershipRegistry(initialOwner);
        vm.stopBroadcast();

        console2.log("MembershipRegistry deployed at:", address(registry));
        console2.log("Owner:", registry.owner());
    }
}
