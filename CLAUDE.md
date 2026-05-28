# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

**Fase 1 cerrada y validada end-to-end.** El plugin de gas + el contrato `MembershipRegistry` están
implementados, testeados (82 tests Java + 22 tests Solidity verdes) y validados con un smoke test
E2E de 8/8 casos. **Fase 1.5** suma un `PluginTransactionPoolValidator` que rechaza upfront
(en `eth_sendRawTransaction`) las TXs que nunca podrían entrar a ningún bloque. **Fase 1.6** suma
métodos JSON-RPC custom (`gasMembership_*`) para notificar al cliente el motivo/bloque del rechazo.

| Carpeta | Qué hay |
|---|---|
| `plugin/` | Plugin Java (Gradle 8.10.2, Java 21, Besu 25.8.0). JAR shadowed listo en `build/libs/`. Selector (Fase 1) + validator (Fase 1.5) + RPC custom (Fase 1.6). |
| `contracts/` | `MembershipRegistry.sol` (producción) + `GasBurner.sol` (helper para tests E2E) con Foundry + OpenZeppelin 5.1.0. Submódulos en `lib/` (NO commiteados — `forge install` para reinstalar). |
| `node/` | Besu 25.8-falcon QBFT local con `start-besu.sh` y `smoke-test.sh`. |
| `pruebas/` | Scripts Hardhat + ethers v6 para deployar contratos desde una cuenta BASIC (`deploy:basic` caso positivo, `deploy:exceed` caso negativo que valida que el validator rechaza). Ver `pruebas/README.md`. |
| `docs/` | Documentación completa en 5 archivos — empezar por `docs/README.md`. |

## Read this first

La documentación detallada vive en `docs/` y está organizada por tema:

- **[`docs/README.md`](docs/README.md)** — Índice, descripción general, quick start.
- **[`docs/01-requisitos-y-logica.md`](docs/01-requisitos-y-logica.md)** — Modelo de membresía, cuotas, comportamiento ante violaciones, decisiones de producto. El "qué".
- **[`docs/02-arquitectura.md`](docs/02-arquitectura.md)** — Componentes Java, flujo del selector, gas accounting, concurrencia, dependencias. El "cómo".
- **[`docs/03-contratos.md`](docs/03-contratos.md)** — `MembershipRegistry` + tests Foundry, incluyendo los tripwires cross-stack.
- **[`docs/04-smoke-test-e2e.md`](docs/04-smoke-test-e2e.md)** — Test end-to-end al detalle: 8 casos con resultados verificados.

**No dupliques info entre `CLAUDE.md` y `docs/`** — los docs son la fuente de verdad. Acá solo
queda lo que ayuda a Claude Code a navegar el repo y evitar errores comunes.

## Big-picture architecture

Dos puntos de evaluación: validator al admitir al pool, selector al armar el bloque.

```
Wallet → TX → eth_sendRawTransaction
                  │
                  └── [GasMembershipTransactionValidator.validateTransaction]  ← Fase 1.5
                          │
                          ├── TierCache.getOrLoad(sender, pendingBlockNum, client::getTier)
                          │
                          └── decide:
                              ├── NONE                              → reject (no membership)
                              ├── BASIC/STANDARD + gas > tierQuota  → reject (tx exceeds tier quota)
                              └── resto                              → admit al txpool

TX admitida → Besu txpool → [GasMembershipTransactionSelector.evaluateTransactionPreProcessing]
                                       │
                                       ├── TierCache.getOrLoad(sender, blockNum, client::getTier)
                                       │       └── MembershipContractClient
                                       │              └── TransactionSimulationService.simulate(eth_call interno)
                                       │                     └── MembershipRegistry.getTier(address) on-chain
                                       │
                                       ├── BlockGasTracker.onBlockChange / getUsed / totalUsed
                                       │
                                       └── decide: SELECTED / invalid / invalidTransient

Post-processing → BlockGasTracker.add(sender, gasUsed REAL)

Cualquier rechazo (validator o selector) → RejectionEventBus.emit(...)
                                              └── gasMembership_getRejection(txHash)  ← Fase 1.6
                                              └── gasMembership_listRejectionsBySender(sender)
```

Detalle visual y de cada componente en `docs/02-arquitectura.md`. El validator y el
selector **comparten la misma `TierCache` y el mismo `RejectionEventBus`** — un solo lookup al
contrato sirve a ambos, y ambos escriben los rechazos al mismo bus que exponen los métodos RPC.

## Cosas que solo se descubren leyendo varios archivos

**Dependencias de Besu**: viven en `https://hyperledger.jfrog.io/artifactory/besu-maven` (NO
Maven Central). El groupId es `org.hyperledger.besu` (artifactos `besu-plugin-api`,
`besu-datatypes`, `besu-evm`). Versión publicada `25.8.0`, mientras el binario local es
`25.8.0.4` (build interno binario-compatible).

**Tuweni cambió de groupId**: `org.apache.tuweni` → `io.consensys.tuweni` en Maven Central. El
package Java sigue siendo `org.apache.tuweni.bytes.*`.

**JDK 21 keg-only en Homebrew**: `gradle.properties` apunta explícitamente a
`/usr/local/opt/openjdk@21/...` porque Gradle no detecta los keg-only por auto-detection. Si
movés el JDK 21 a otra ruta, actualizá esa property.

**RPC en puerto 4545, NO 8545**: `node/config.toml` usa puertos no-default
(`4545/4546/4547` para HTTP/WS/GraphQL). Cualquier curl o cast que veas en ejemplos del web
asumiendo 8545 hay que traducirlo.

**Plugin discovery**: Besu busca plugins en `${besu.plugins.dir}` o, si no está seteada, en
`${besu.home}/plugins/` (= dir de instalación de Besu, NO `data-path`). `start-besu.sh` setea
`besu.plugins.dir=node/data/plugins` para aislamiento.

**Selector ABI `0xb45aae52`** hardcoded en `MembershipContractClient.GET_TIER_SELECTOR`.
Verificado por un test Solidity (`test_GetTierSelectorMatchPluginHardcoded` en
`MembershipRegistry.t.sol`). Si la firma del contrato cambia, los tests detectan el drift.

**Enum cross-stack**: el orden `NONE=0, BASIC=1, STANDARD=2, PREMIUM=3, WHITELISTED=4` está
duplicado entre `Tier.java` y `IMembershipRegistry.sol`. Dos tests (uno por lado) verifican
que los enums están sincronizados.

**Namespace RPC custom debe habilitarse en `config.toml`** (Fase 1.6): los métodos
`gasMembership_*` no se exponen a menos que `GASMEMBERSHIP` esté en `rpc-http-api` y
`rpc-ws-api`. Besu matchea el namespace case-insensitive por prefijo
(`RpcEndpointServiceImpl.hasNamespace` = `methodName.toUpperCase().startsWith(api.toUpperCase())`).
Sin esa entrada, el cliente recibe `Method not found` aunque el plugin haya registrado el método.

## Test helpers

**`contracts/src/GasBurner.sol`** — contrato auxiliar para el smoke E2E. Expone
`consumeGas(target)`, que gasta aproximadamente `target` gas (precisión ±5–10% para
targets ≥ 50K) y devuelve el consumo real. Usa SSTOREs sobre 16 slots pre-calentados en el
constructor (rama warm-non-zero, ~5000 gas por iteración — más predecible que slots cold).

Sirve para validar los topes por tier (BASIC=500K, STANDARD=5M, PREMIUM=10M) en una sola TX
con consumo conocido, sin tener que disparar decenas de transferencias reales y estimar.
NO es parte del producto — vive en `contracts/src/` solo porque comparte el toolchain de
Foundry, pero no debe deployarse en chains productivas.

## Comandos típicos

```bash
# Java unit tests (82 tests: selector + validator + cache + tracker + client + bus + plugin)
cd plugin && ./gradlew test

# Solidity tests (22 tests: 18 MembershipRegistry + 4 GasBurner)
cd contracts && forge test

# Build del JAR del plugin
cd plugin && ./gradlew build
# Output: plugin/build/libs/besu-gas-membership-plugin-0.1.0-SNAPSHOT.jar

# Arrancar Besu local (sin plugin)
./node/start-besu.sh

# Smoke test end-to-end (arranca Besu, deploya, asigna tiers, valida 8 casos)
./node/smoke-test.sh

# Reinstalar deps de Foundry (lib/ no se commitea)
cd contracts && forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 \
                              foundry-rs/forge-std --no-commit
```

## Lo que está pendiente

Fase 2 — cuota mensual on-chain + bloqueo 5 min. Componentes nuevos:

- `UsageMeter.sol` (contador acumulado on-chain por cuenta).
- Block listener Java que tras cada bloque commitea uso al `UsageMeter` (batch).
- `BlockedAddressRegistry` (TTL 5 min, en memoria).

Decisiones pendientes (no bloquean nada): definición exacta de "mes", cadencia de commits batch,
multisig para owner en producción. Detalle en `docs/01-requisitos-y-logica.md § 9 Pendientes`.

## When implementing

- **No bundlear deps de Besu**: `compileOnly` para `besu-plugin-api`, `besu-datatypes`, `besu-evm`.
- **No tocar `tuweni-bytes` versión** sin verificar que el binario local de Besu use la misma —
  los tipos cruzan el plugin boundary.
- **No hardcodear el contract address** en código — siempre via `ratelimit.membershipContract`.
- **No mover el selector hardcoded** sin actualizar `test_GetTierSelectorMatchPluginHardcoded`.
- **`/usr/bin/curl`, `/usr/bin/grep`, etc.**: en scripts que se invocan desde subshells (como
  los `for` con eval) usar rutas absolutas para evitar problemas de PATH.
