// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Test} from "forge-std/Test.sol";
import {UsageMeter} from "../src/UsageMeter.sol";
import {IUsageMeter} from "../src/IUsageMeter.sol";

contract UsageMeterTest is Test {
    UsageMeter internal meter;
    address internal owner = address(0xA11CE);
    address internal recorder = address(0x4EC0); // "recorder" node account
    address internal user = address(0xB0B);
    address internal user2 = address(0xCA11);

    // Período de ejemplo: mes calendario UTC = year*12 + month0. Mayo 2026 = 2026*12 + 4.
    uint256 internal constant PERIOD = 2026 * 12 + 4;

    event UsageRecorded(uint256 indexed periodId, address indexed account, uint256 newTotal);
    event RecorderUpdated(address indexed recorder);

    function setUp() public {
        meter = new UsageMeter(owner, recorder);
    }

    function _batch1(address a, uint256 g)
        internal
        pure
        returns (address[] memory accounts, uint256[] memory gas)
    {
        accounts = new address[](1);
        gas = new uint256[](1);
        accounts[0] = a;
        gas[0] = g;
    }

    function test_InitialUsageIsZero() public view {
        assertEq(meter.getUsage(PERIOD, user), 0);
    }

    function test_InitialRecorderSet() public view {
        assertEq(meter.recorder(), recorder);
    }

    function test_RecorderCanRecord() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 1000);
        vm.prank(recorder);
        meter.recordUsageBatch(PERIOD, a, g);
        assertEq(meter.getUsage(PERIOD, user), 1000);
    }

    function test_RecordAccumulates() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 1000);
        vm.startPrank(recorder);
        meter.recordUsageBatch(PERIOD, a, g);
        g[0] = 250;
        meter.recordUsageBatch(PERIOD, a, g);
        vm.stopPrank();
        assertEq(meter.getUsage(PERIOD, user), 1250);
    }

    function test_PeriodsAreIsolated() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 1000);
        vm.prank(recorder);
        meter.recordUsageBatch(PERIOD, a, g);
        assertEq(meter.getUsage(PERIOD + 1, user), 0);
        assertEq(meter.getUsage(PERIOD, user), 1000);
    }

    function test_BatchUpdatesMultipleAccounts() public {
        address[] memory a = new address[](2);
        uint256[] memory g = new uint256[](2);
        a[0] = user;
        a[1] = user2;
        g[0] = 10;
        g[1] = 20;
        vm.prank(recorder);
        meter.recordUsageBatch(PERIOD, a, g);
        assertEq(meter.getUsage(PERIOD, user), 10);
        assertEq(meter.getUsage(PERIOD, user2), 20);
    }

    function test_RecordEmitsEvent() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 777);
        vm.expectEmit(true, true, false, true);
        emit UsageRecorded(PERIOD, user, 777);
        vm.prank(recorder);
        meter.recordUsageBatch(PERIOD, a, g);
    }

    function test_NonRecorderCannotRecord() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 1000);
        vm.prank(user);
        vm.expectRevert(UsageMeter.NotRecorder.selector);
        meter.recordUsageBatch(PERIOD, a, g);
    }

    function test_OwnerCannotRecordUnlessRecorder() public {
        (address[] memory a, uint256[] memory g) = _batch1(user, 1000);
        vm.prank(owner);
        vm.expectRevert(UsageMeter.NotRecorder.selector);
        meter.recordUsageBatch(PERIOD, a, g);
    }

    function test_LengthMismatchReverts() public {
        address[] memory a = new address[](2);
        uint256[] memory g = new uint256[](1);
        a[0] = user;
        a[1] = user2;
        g[0] = 1;
        vm.prank(recorder);
        vm.expectRevert(UsageMeter.LengthMismatch.selector);
        meter.recordUsageBatch(PERIOD, a, g);
    }

    function test_OwnerCanSetRecorder() public {
        vm.expectEmit(true, false, false, false);
        emit RecorderUpdated(user2);
        vm.prank(owner);
        meter.setRecorder(user2);
        assertEq(meter.recorder(), user2);

        (address[] memory a, uint256[] memory g) = _batch1(user, 5);
        vm.prank(user2);
        meter.recordUsageBatch(PERIOD, a, g);
        assertEq(meter.getUsage(PERIOD, user), 5);
    }

    function test_NonOwnerCannotSetRecorder() public {
        vm.prank(user);
        vm.expectRevert();
        meter.setRecorder(user);
    }

    function test_SetRecorderZeroReverts() public {
        vm.prank(owner);
        vm.expectRevert(UsageMeter.ZeroRecorder.selector);
        meter.setRecorder(address(0));
    }

    // === Tests cross-stack: pinean los selectores que el plugin Java hardcodea. ===
    // Si la firma del contrato cambia, estos tests fallan y avisan que hay que actualizar el
    // UsageMeterClient del plugin (constantes GET_USAGE_SELECTOR / RECORD_USAGE_BATCH_SELECTOR).

    function test_GetUsageSelectorMatchPluginHardcoded() public pure {
        bytes4 expected = 0x44202d6e; // getUsage(uint256,address)
        assertEq(IUsageMeter.getUsage.selector, expected);
    }

    function test_RecordUsageBatchSelectorMatchPluginHardcoded() public pure {
        bytes4 expected = 0x1e5092f1; // recordUsageBatch(uint256,address[],uint256[])
        assertEq(IUsageMeter.recordUsageBatch.selector, expected);
    }
}
