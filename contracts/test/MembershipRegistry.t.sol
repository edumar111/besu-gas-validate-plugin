// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Test} from "forge-std/Test.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IMembershipRegistry} from "../src/IMembershipRegistry.sol";
import {MembershipRegistry} from "../src/MembershipRegistry.sol";

contract MembershipRegistryTest is Test {
    MembershipRegistry private registry;

    address private constant OWNER = address(0x0a11);
    address private constant ALICE = address(0xA11CE);
    address private constant BOB = address(0xB0B);
    address private constant CAROL = address(0xCA801);
    address private constant ATTACKER = address(0xBAD);

    /// @dev Eventos duplicados para usar con expectEmit.
    event TierAssigned(address indexed account, IMembershipRegistry.Tier tier);
    event TierRevoked(address indexed account);

    function setUp() public {
        registry = new MembershipRegistry(OWNER);
    }

    // === Estado inicial ===

    function test_OwnerSeteadoEnConstructor() public view {
        assertEq(registry.owner(), OWNER);
    }

    function test_TierInicialEsNone() public view {
        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.NONE));
        assertEq(uint8(registry.getTier(BOB)), uint8(IMembershipRegistry.Tier.NONE));
    }

    function test_OnChainEnumValuesMatchPluginExpectation() public pure {
        // Estos valores son contractuales con com.lacnet.besu.gas.model.Tier
        // del plugin Java. Si esto cambia, el plugin se rompe.
        assertEq(uint8(IMembershipRegistry.Tier.NONE), 0);
        assertEq(uint8(IMembershipRegistry.Tier.BASIC), 1);
        assertEq(uint8(IMembershipRegistry.Tier.STANDARD), 2);
        assertEq(uint8(IMembershipRegistry.Tier.PREMIUM), 3);
        assertEq(uint8(IMembershipRegistry.Tier.WHITELISTED), 4);
    }

    // === setTier ===

    function test_OwnerPuedeAsignarTodosLosTiers() public {
        vm.startPrank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.BASIC);
        registry.setTier(BOB, IMembershipRegistry.Tier.STANDARD);
        registry.setTier(CAROL, IMembershipRegistry.Tier.PREMIUM);
        vm.stopPrank();

        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.BASIC));
        assertEq(uint8(registry.getTier(BOB)), uint8(IMembershipRegistry.Tier.STANDARD));
        assertEq(uint8(registry.getTier(CAROL)), uint8(IMembershipRegistry.Tier.PREMIUM));
    }

    function test_OwnerPuedeAsignarWhitelisted() public {
        vm.prank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.WHITELISTED);
        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.WHITELISTED));
    }

    function test_SetTierEmiteEvento() public {
        vm.expectEmit(true, false, false, true);
        emit TierAssigned(ALICE, IMembershipRegistry.Tier.STANDARD);

        vm.prank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.STANDARD);
    }

    function test_SetTierSobrescribeTierPrevio() public {
        vm.startPrank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.BASIC);
        registry.setTier(ALICE, IMembershipRegistry.Tier.PREMIUM);
        vm.stopPrank();
        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.PREMIUM));
    }

    function test_NoOwnerNoPuedeAsignar() public {
        vm.prank(ATTACKER);
        vm.expectRevert(
            abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, ATTACKER)
        );
        registry.setTier(ALICE, IMembershipRegistry.Tier.PREMIUM);
    }

    function test_SetTierConNoneRevierte() public {
        vm.prank(OWNER);
        vm.expectRevert(MembershipRegistry.UseRemoveToRevoke.selector);
        registry.setTier(ALICE, IMembershipRegistry.Tier.NONE);
    }

    // === setTierBatch ===

    function test_BatchAsignaTodos() public {
        address[] memory accs = new address[](3);
        accs[0] = ALICE;
        accs[1] = BOB;
        accs[2] = CAROL;
        IMembershipRegistry.Tier[] memory tiers = new IMembershipRegistry.Tier[](3);
        tiers[0] = IMembershipRegistry.Tier.BASIC;
        tiers[1] = IMembershipRegistry.Tier.STANDARD;
        tiers[2] = IMembershipRegistry.Tier.PREMIUM;

        vm.prank(OWNER);
        registry.setTierBatch(accs, tiers);

        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.BASIC));
        assertEq(uint8(registry.getTier(BOB)), uint8(IMembershipRegistry.Tier.STANDARD));
        assertEq(uint8(registry.getTier(CAROL)), uint8(IMembershipRegistry.Tier.PREMIUM));
    }

    function test_BatchLengthMismatchRevierte() public {
        address[] memory accs = new address[](2);
        accs[0] = ALICE;
        accs[1] = BOB;
        IMembershipRegistry.Tier[] memory tiers = new IMembershipRegistry.Tier[](1);
        tiers[0] = IMembershipRegistry.Tier.BASIC;

        vm.prank(OWNER);
        vm.expectRevert(MembershipRegistry.LengthMismatch.selector);
        registry.setTierBatch(accs, tiers);
    }

    function test_BatchConNoneRevierte() public {
        address[] memory accs = new address[](2);
        accs[0] = ALICE;
        accs[1] = BOB;
        IMembershipRegistry.Tier[] memory tiers = new IMembershipRegistry.Tier[](2);
        tiers[0] = IMembershipRegistry.Tier.BASIC;
        tiers[1] = IMembershipRegistry.Tier.NONE;

        vm.prank(OWNER);
        vm.expectRevert(MembershipRegistry.UseRemoveToRevoke.selector);
        registry.setTierBatch(accs, tiers);
    }

    function test_BatchNoOwnerRevierte() public {
        address[] memory accs = new address[](1);
        accs[0] = ALICE;
        IMembershipRegistry.Tier[] memory tiers = new IMembershipRegistry.Tier[](1);
        tiers[0] = IMembershipRegistry.Tier.BASIC;

        vm.prank(ATTACKER);
        vm.expectRevert(
            abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, ATTACKER)
        );
        registry.setTierBatch(accs, tiers);
    }

    // === removeMember ===

    function test_RemoveMemberDejaTierEnNone() public {
        vm.startPrank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.PREMIUM);
        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.PREMIUM));
        registry.removeMember(ALICE);
        vm.stopPrank();

        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.NONE));
    }

    function test_RemoveMemberEmiteEvento() public {
        vm.prank(OWNER);
        registry.setTier(ALICE, IMembershipRegistry.Tier.BASIC);

        vm.expectEmit(true, false, false, false);
        emit TierRevoked(ALICE);

        vm.prank(OWNER);
        registry.removeMember(ALICE);
    }

    function test_RemoveMemberSobreNoneEsNoOpExceptoEmit() public {
        vm.prank(OWNER);
        registry.removeMember(ALICE); // never had a tier; should not revert
        assertEq(uint8(registry.getTier(ALICE)), uint8(IMembershipRegistry.Tier.NONE));
    }

    function test_RemoveMemberNoOwnerRevierte() public {
        vm.prank(ATTACKER);
        vm.expectRevert(
            abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, ATTACKER)
        );
        registry.removeMember(ALICE);
    }

    // === ABI / selector matching ===

    function test_GetTierSelectorMatchPluginHardcoded() public pure {
        // El plugin Java hardcodea el selector 0xb45aae52 para getTier(address).
        // Si este test falla, el plugin necesita actualizar GET_TIER_SELECTOR
        // en MembershipContractClient.java.
        bytes4 expected = 0xb45aae52;
        bytes4 actual = IMembershipRegistry.getTier.selector;
        assertEq(actual, expected, "selector mismatch: actualizar el plugin");
    }
}
