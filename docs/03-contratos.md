# 03 — Contratos y Tests

> `MembershipRegistry.sol` + `UsageMeter.sol` (producción) + `GasBurner.sol` (helper para tests E2E)
> y su batería de **37 tests Foundry** (18 MembershipRegistry + 4 GasBurner + 15 UsageMeter). Incluye
> los "tripwires cross-stack" que detectan drift entre el plugin Java y los contratos Solidity.
> `UsageMeter` (Fase 2) se detalla en § 9 y en [`07-fase-2-cuota-mensual.md`](./07-fase-2-cuota-mensual.md).
>
> Si buscás el "qué" del modelo de membresía, ver
> [`01-requisitos-y-logica.md`](./01-requisitos-y-logica.md). Para integración con el plugin,
> ver [`02-arquitectura.md § 2.3 MembershipContractClient`](./02-arquitectura.md#23-membershipcontractclient).

---

## 1. Stack y layout

**Stack**: Foundry 1.4.4 (`forge`/`cast`/`anvil`), Solidity 0.8.20, OpenZeppelin Contracts 5.1.0.

**Por qué Foundry y no Hardhat**: los tests son Solidity puro, sin JS runner. Los 37 tests
corren en **~5 ms** vs ~5-10 s con Hardhat. Para una librería pequeña y bien acotada, no
necesitamos JS bindings.

```
contracts/
├── foundry.toml                   ← solc 0.8.20, remappings @openzeppelin, rpc_endpoints.local
├── lib/                           ← submódulos forge (NO commiteados — reinstalar con forge install)
│   ├── forge-std/                 ← v1.16.1
│   └── openzeppelin-contracts/    ← v5.1.0
├── src/
│   ├── IMembershipRegistry.sol    ← interfaz
│   ├── MembershipRegistry.sol     ← implementación con Ownable (producción)
│   └── GasBurner.sol              ← helper para tests E2E (NO producción)
├── test/
│   ├── MembershipRegistry.t.sol   ← 18 tests
│   └── GasBurner.t.sol            ← 4 tests
└── script/
    └── Deploy.s.sol               ← forge script para deploy
```

### Sobre `GasBurner.sol`

Contrato auxiliar para los tests end-to-end (no parte del producto). Expone `consumeGas(target)`
que gasta aproximadamente `target` gas vía SSTOREs sobre 16 slots pre-calentados (~5000 gas por
iteración, precisión ±5–10% para targets ≥ 50K). Sirve para disparar TXs con consumo conocido
contra los topes de tier (BASIC 500K, STANDARD 5M, PREMIUM 10M) en una sola transacción —
evita tener que mandar decenas de transferencias para estimar consumo. NO debe deployarse en
chains productivas.

### Reinstalar deps

`contracts/lib/` está excluido del repo por tamaño (~13MB). Quien clone el repo debe correr:

```bash
cd contracts
forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 foundry-rs/forge-std --no-commit
```

---

## 2. `foundry.toml`

```toml
[profile.default]
src = "src"
out = "out"
libs = ["lib"]
solc_version = "0.8.20"
optimizer = true
optimizer_runs = 200
via_ir = false
verbosity = 2

remappings = [
    "@openzeppelin/contracts/=lib/openzeppelin-contracts/contracts/",
]

[rpc_endpoints]
local = "http://localhost:4545"
```

**Nota importante**: el endpoint `local` apunta al puerto **4545**, no al 8545 default. Los
comandos `forge script --rpc-url local` y `cast --rpc-url local` usan ese endpoint.

---

## 3. `IMembershipRegistry.sol` (interfaz)

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

interface IMembershipRegistry {
    enum Tier { NONE, BASIC, STANDARD, PREMIUM, WHITELISTED }

    event TierAssigned(address indexed account, Tier tier);
    event TierRevoked(address indexed account);

    function getTier(address account) external view returns (Tier);
    function setTier(address account, Tier tier) external;
    function setTierBatch(address[] calldata accounts, Tier[] calldata tiers) external;
    function removeMember(address account) external;
}
```

**Contrato cross-stack**: el orden y los valores del enum deben coincidir exactamente con
`com.lacnet.besu.gas.model.Tier` del plugin Java. Solidity asigna 0..4 a las constantes en orden
de declaración (`NONE=0, BASIC=1, ..., WHITELISTED=4`) y eso es lo que vuelve serializado en
`getTier(address)`. Si reordenás acá sin tocar el plugin, decodifica tiers incorrectos.

Hay dos tests (uno en cada lado) que tripean si los enums se desincronizan — ver § 5.1.

---

## 4. `MembershipRegistry.sol` (implementación)

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IMembershipRegistry} from "./IMembershipRegistry.sol";

contract MembershipRegistry is IMembershipRegistry, Ownable {
    mapping(address => Tier) private _tiers;

    error LengthMismatch();
    error UseRemoveToRevoke();

    constructor(address initialOwner) Ownable(initialOwner) {}

    function getTier(address account) external view override returns (Tier) {
        return _tiers[account];
    }

    function setTier(address account, Tier tier) external override onlyOwner {
        if (tier == Tier.NONE) revert UseRemoveToRevoke();
        _tiers[account] = tier;
        emit TierAssigned(account, tier);
    }

    function setTierBatch(address[] calldata accounts, Tier[] calldata tiers)
        external override onlyOwner
    {
        if (accounts.length != tiers.length) revert LengthMismatch();
        for (uint256 i = 0; i < accounts.length; i++) {
            if (tiers[i] == Tier.NONE) revert UseRemoveToRevoke();
            _tiers[accounts[i]] = tiers[i];
            emit TierAssigned(accounts[i], tiers[i]);
        }
    }

    function removeMember(address account) external override onlyOwner {
        delete _tiers[account];
        emit TierRevoked(account);
    }
}
```

### Decisiones de diseño del contrato

| # | Decisión | Razón |
|---|---|---|
| 1 | OpenZeppelin `Ownable` 5.x (`constructor(initialOwner)`) | Owner único explícito; en OZ 5 el constructor requiere el owner inicial (en 4.x era `_msgSender()` implícito) |
| 2 | Custom errors (`UseRemoveToRevoke`, `LengthMismatch`) | Más baratos que `require` con string. Selectores ABI estables para el frontend |
| 3 | `setTier` rechaza `NONE` | `NONE` es la ausencia de membresía — para revocar usar `removeMember`. Evita ambigüedad: una cuenta no debería "tener tier NONE" en el storage; debería simplemente no estar en el mapping |
| 4 | `removeMember` siempre emite evento (incluso si la cuenta no tiene tier) | Idempotente. El consumidor del evento puede reconciliar sin error |
| 5 | Sin expiración de tiers | Fase 1: el owner gestiona manualmente. Expiración temporal queda para Fase 2 |
| 6 | `getTier` es view (no costó gas) | El plugin la llama por cada TX vía `eth_call` interno — debe ser barata |

### Lo que el contrato NO hace (en Fase 1)

- No registra fechas de alta/baja (no se necesita en Fase 1).
- No cobra ni acepta pagos — el pricing vive en un backend externo.
- No tiene `pause()` ni roles avanzados — un solo owner.
- No tiene contador mensual — ese es **otro contrato**, `UsageMeter.sol`, implementado en Fase 2
  (ver § 9 y [`07-fase-2-cuota-mensual.md`](./07-fase-2-cuota-mensual.md)).

---

## 5. Tests — `MembershipRegistry.t.sol`

**18 tests en 5 ms** (más 4 tests del `GasBurner.t.sol` que se cubren en § 5.8). Cubren toda
la API + dos "tripwires cross-stack" que detectan drift con el plugin Java.

### 5.1 Tests cross-stack (los más importantes)

#### `test_OnChainEnumValuesMatchPluginExpectation`

```solidity
function test_OnChainEnumValuesMatchPluginExpectation() public pure {
    // Contractuales con com.lacnet.besu.gas.model.Tier del plugin Java.
    assertEq(uint8(IMembershipRegistry.Tier.NONE), 0);
    assertEq(uint8(IMembershipRegistry.Tier.BASIC), 1);
    assertEq(uint8(IMembershipRegistry.Tier.STANDARD), 2);
    assertEq(uint8(IMembershipRegistry.Tier.PREMIUM), 3);
    assertEq(uint8(IMembershipRegistry.Tier.WHITELISTED), 4);
}
```

Si alguien reordena el enum Solidity, este test falla. Su contraparte Java
(`TierTest.onChainValuesMatchSolidityEnumOrder`) hace el assert simétrico.

#### `test_GetTierSelectorMatchPluginHardcoded`

```solidity
function test_GetTierSelectorMatchPluginHardcoded() public pure {
    // El plugin Java hardcodea 0xb45aae52 en MembershipContractClient.GET_TIER_SELECTOR.
    bytes4 expected = 0xb45aae52;
    bytes4 actual = IMembershipRegistry.getTier.selector;
    assertEq(actual, expected, "selector mismatch: actualizar el plugin");
}
```

Si alguien renombra `getTier` a `getTierOf` (cambio sutil que rompe el plugin sin causar error
de compilación), este test falla con mensaje claro indicando qué hacer.

### 5.2 Tests de estado inicial

| Test | Qué verifica |
|---|---|
| `test_OwnerSeteadoEnConstructor` | El owner queda exactamente el address pasado al constructor |
| `test_TierInicialEsNone` | Cualquier address no asignada devuelve `Tier.NONE` (default del mapping) |

### 5.3 Tests de `setTier`

| Test | Caso |
|---|---|
| `test_OwnerPuedeAsignarTodosLosTiers` | BASIC, STANDARD, PREMIUM se asignan correctamente |
| `test_OwnerPuedeAsignarWhitelisted` | WHITELISTED también es asignable normalmente |
| `test_SetTierEmiteEvento` | `emit TierAssigned(account, tier)` con `expectEmit` |
| `test_SetTierSobrescribeTierPrevio` | Asignar BASIC y luego PREMIUM deja PREMIUM |
| `test_NoOwnerNoPuedeAsignar` | Revert con `OwnableUnauthorizedAccount(attacker)` |
| `test_SetTierConNoneRevierte` | Revert con `UseRemoveToRevoke()` |

### 5.4 Tests de `setTierBatch`

| Test | Caso |
|---|---|
| `test_BatchAsignaTodos` | 3 cuentas con 3 tiers diferentes, todas quedan asignadas |
| `test_BatchLengthMismatchRevierte` | `accounts.length=2, tiers.length=1` → `LengthMismatch()` |
| `test_BatchConNoneRevierte` | Si una entry tiene `Tier.NONE`, revierte (sin asignar ninguna) |
| `test_BatchNoOwnerRevierte` | `OwnableUnauthorizedAccount` cuando no es owner |

### 5.5 Tests de `removeMember`

| Test | Caso |
|---|---|
| `test_RemoveMemberDejaTierEnNone` | Asignar PREMIUM, luego remove → `getTier` devuelve NONE |
| `test_RemoveMemberEmiteEvento` | `emit TierRevoked(account)` |
| `test_RemoveMemberSobreNoneEsNoOpExceptoEmit` | Remove a cuenta sin tier no revierte (idempotente) |
| `test_RemoveMemberNoOwnerRevierte` | `OwnableUnauthorizedAccount` |

### 5.6 Recursos auxiliares de los tests

Los tests usan `forge-std/Test.sol` (`Test` base class con `vm.prank`, `vm.expectEmit`, etc.):

```solidity
contract MembershipRegistryTest is Test {
    MembershipRegistry private registry;

    address private constant OWNER = address(0x0a11);
    address private constant ALICE = address(0xA11CE);
    // ...

    event TierAssigned(address indexed account, IMembershipRegistry.Tier tier);
    event TierRevoked(address indexed account);

    function setUp() public {
        registry = new MembershipRegistry(OWNER);
    }
}
```

Las direcciones constantes son strings hex pequeñas y memorables, no relacionadas con cuentas
reales. Suficiente para tests aislados.

### 5.7 Correr los tests

```bash
cd contracts

# Suite completa
forge test

# Con output verbose (muestra gas usado por test)
forge test -vv

# Un test específico
forge test --match-test test_GetTierSelectorMatchPluginHardcoded

# Mostrar gas snapshot
forge test --gas-report
```

Salida típica:

```
Ran 4 tests for test/GasBurner.t.sol:GasBurnerTest
[PASS] test_ConsumoCreceConTarget() (gas: ...)
[PASS] test_PrecisionEnTargetsRelevantes() (gas: ...)
[PASS] test_TargetCeroNoLanza() (gas: ...)
[PASS] test_TargetMuyChicoNoLanza() (gas: ...)

Ran 18 tests for test/MembershipRegistry.t.sol:MembershipRegistryTest
[PASS] test_BatchAsignaTodos() (gas: 91417)
[PASS] test_BatchConNoneRevierte() (gas: 38036)
...
Suite result: ok. 22 passed; 0 failed; 0 skipped; finished in 5.08ms (12.65ms CPU time)
```

### 5.8 Tests del `GasBurner.t.sol`

Cuatro tests que validan la precisión del helper `GasBurner.consumeGas(target)`:

| Test | Qué cubre |
|---|---|
| `test_PrecisionEnTargetsRelevantes` | Verifica precisión ±10% en los targets que usan los tests E2E: 100K, 200K, 500K, 4M, 5M, 10M. |
| `test_TargetMuyChicoNoLanza` | Edge case: `target=100` → loop no entra, devuelve overhead mínimo (~50 gas), no revierte. |
| `test_TargetCeroNoLanza` | Edge case: `target=0` → idem caso anterior. |
| `test_ConsumoCreceConTarget` | Monotonicidad: `consumeGas` es monótono creciente en `target`. |

`GasBurner` no participa de la lógica del plugin; está acá porque comparte el toolchain de Foundry.

---

## 6. Deploy script — `Deploy.s.sol`

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Script, console2} from "forge-std/Script.sol";
import {MembershipRegistry} from "../src/MembershipRegistry.sol";

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
```

**`vm.envOr("OWNER", msg.sender)`**: si seteás la variable de entorno `OWNER`, ese será el
owner. Si no, queda el broadcaster (el address derivado de `--private-key`).

### Uso

```bash
export PRIVATE_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
# Opcional:
# export OWNER=0xSOME_ADDRESS

cd contracts && forge script script/Deploy.s.sol \
    --rpc-url local \
    --private-key $PRIVATE_KEY \
    --broadcast \
    --legacy \
    --skip-simulation
```

**Flags importantes**:

- `--legacy` → forza TX tipo 0 (sin EIP-1559). Necesario porque la red usa
  `baseFeePerGas = 0` y `min-gas-price = 0`.
- `--skip-simulation` → la red privada no tiene Etherscan, y el simulador puede pifiarse en
  configuraciones zero-gas. Si querés revertir esto, asegurate de tener fork bien configurado.

**Salida**:

```
== Logs ==
  MembershipRegistry deployed at: 0x8464135c8F25Da09e49BC8782676a84730C318bC
  Owner: 0x70997970C51812dc3A010C7d01b50e0d17dc79C8

==========================
ONCHAIN EXECUTION COMPLETE & SUCCESSFUL.
Transactions saved to: contracts/broadcast/Deploy.s.sol/650540/run-latest.json
```

La dirección deployada se anota como `ratelimit.membershipContract` al arrancar Besu con el
plugin (ver [`04-smoke-test-e2e.md § 4`](./04-smoke-test-e2e.md)).

---

## 7. Operación: asignar y consultar tiers

Una vez deployado, usá `cast` para operar contra el contrato. Asumamos:

```bash
CONTRACT=0x8464135c8F25Da09e49BC8782676a84730C318bC
OWNER_PK=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
RPC=http://localhost:4545
```

### 7.1 Asignar un tier individual

```bash
cast send $CONTRACT \
    "setTier(address,uint8)" \
    0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC \
    1 \
    --rpc-url $RPC --private-key $OWNER_PK --legacy
```

Los valores del enum: `1=BASIC, 2=STANDARD, 3=PREMIUM, 4=WHITELISTED`. **No mandes 0 (NONE)** —
revierte con `UseRemoveToRevoke()`.

### 7.2 Asignar batch

```bash
cast send $CONTRACT \
    "setTierBatch(address[],uint8[])" \
    "[$ALICE,$BOB,$CAROL,$DAVE]" \
    "[1,2,3,4]" \
    --rpc-url $RPC --private-key $OWNER_PK --legacy
```

### 7.3 Consultar un tier

```bash
cast call $CONTRACT \
    "getTier(address)(uint8)" \
    0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC \
    --rpc-url $RPC
# Output: 1
```

El `(uint8)` después de la firma fuerza el decode como uint, así obtenés `1` en vez de
`0x0000...01`.

### 7.4 Remover una membresía

```bash
cast send $CONTRACT \
    "removeMember(address)" \
    0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC \
    --rpc-url $RPC --private-key $OWNER_PK --legacy
```

Después, `getTier(...)` devuelve `0` (NONE) y se emite `TierRevoked(account)`.

---

## 8. Consideraciones de seguridad

- **Owner único = single point of failure**. En MVP es aceptable porque el owner es una cuenta
  custodiada del operador. **Para producción**, mover a multisig (Safe / Gnosis) o role-based
  con OpenZeppelin AccessControl.
- **Eventos para auditoría externa**: cualquier cambio de tier emite `TierAssigned` o
  `TierRevoked`. El backend de billing debe escuchar esos eventos y conciliar.
- **`getTier` no reentrante** porque es `view` — el plugin lo llama miles de veces sin riesgo.
- **No hay rate limit en `setTier`** — el owner puede asignar cuantos quiera por bloque. Si esto
  llegara a ser problemático, un upgrade futuro podría agregar un cooldown.

---

## 9. `UsageMeter.sol` (Fase 2 — implementado)

La cuota mensual se persiste en un **contrato separado** del `MembershipRegistry`. Detalle completo
del flujo, el contador híbrido y el nodo recorder en
[`07-fase-2-cuota-mensual.md`](./07-fase-2-cuota-mensual.md).

```solidity
interface IUsageMeter {
    event UsageRecorded(uint256 indexed periodId, address indexed account, uint256 newTotal);
    event RecorderUpdated(address indexed recorder);
    function getUsage(uint256 periodId, address account) external view returns (uint256);
    function recordUsageBatch(uint256 periodId, address[] accounts, uint256[] gasDeltas) external; // onlyRecorder
    function setRecorder(address recorder) external;  // onlyOwner
    function recorder() external view returns (address);
}
```

Decisiones de diseño:

1. **Guarda consumo, no cuotas**: `getUsage` devuelve el gas acumulado por `(periodId, cuenta)`. Las
   cuotas viven en la config del plugin (una sola fuente de verdad, igual que las per-block). No hay
   `getRemainingQuota` on-chain — el plugin computa el remaining contra su config.
2. **`recordUsageBatch` acumulativo + onlyRecorder**: el plugin (desde un nodo recorder designado)
   envía solo el delta no commiteado; el contrato lo suma. Rol `recorder` separado del `owner`.
3. **Contratos separados** (`MembershipRegistry` + `UsageMeter`): independencia — el upgrade del meter
   no afecta la asignación de tiers ni viceversa.

**Tests cross-stack** en `UsageMeter.t.sol` (15 tests) pinean los selectores que el plugin hardcodea:
`getUsage(uint256,address)` = `0x44202d6e`, `recordUsageBatch(uint256,address[],uint256[])` =
`0x1e5092f1`. Si la firma cambia, el test falla y avisa que hay que actualizar el cliente Java.

---

## 10. Lecturas relacionadas

- [Requisitos y lógica del plugin](./01-requisitos-y-logica.md) — modelo de membresía completo.
- [Arquitectura](./02-arquitectura.md § 2.3) — cómo el plugin llama `getTier`.
- [Smoke test E2E](./04-smoke-test-e2e.md) — deploy real + asignación de tiers + validación.
