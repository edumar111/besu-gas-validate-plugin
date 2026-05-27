// SPDX-License-Identifier: MIT
pragma solidity 0.8.20;

contract Storage {
    uint256 public value;

    function set(uint256 v) external {
        value = v;
    }
}
