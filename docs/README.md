# Besu Gas Membership Plugin — Documentación

> Plugin para Hyperledger Besu que enforza una cuota de gas por bloque según el tier de membresía
> de la cuenta emisora, leído on-chain desde un contrato `MembershipRegistry`. Diseñado para una
> CBDC privada con gas price = 0 sobre QBFT.
>
> **Estado**: Fase 1 cerrada (2026-05-21) + Fase 1.5 (validator al admitir al pool) +
> Fase 1.6 (notificaciones de rechazo vía JSON-RPC custom, 2026-05-28) +
> **Fase 2 (cuota mensual on-chain + bloqueo 5 min, 2026-05-31)**. 168 tests Java +
> 37 Solidity verdes.

---

## Índice de documentos

| # | Documento | Para qué sirve |
|---|---|---|
| 1 | [Requisitos y lógica del plugin](./01-requisitos-y-logica.md) | El **qué**: modelo de membresía, cuotas por bloque y mensual, comportamiento ante violaciones, decisiones de producto. Léelo primero si querés entender qué hace el plugin. |
| 2 | [Arquitectura del plugin](./02-arquitectura.md) | El **cómo**: componentes Java, flujo de validación por TX, gas accounting, concurrencia, dependencias. Cubre selector (Fase 1) y validator (Fase 1.5). |
| 3 | [Contratos y tests](./03-contratos.md) | `MembershipRegistry.sol` + tests Foundry. Incluye los "tripwires cross-stack" que detectan drift entre Solidity y el plugin Java. |
| 4 | [Smoke test end-to-end](./04-smoke-test-e2e.md) | Validación E2E con `cast`: deploy del contrato, asignación de tiers, envío de TXs reales con el plugin activo. Detalla cada uno de los 8 casos cubiertos. |
| 5 | [Fase 1.5: validator y `pruebas/`](./05-fase-1.5-validator-y-pruebas.md) | Plugins implementados (clases Java y interfaces Besu), división de responsabilidades selector/validator, cambios en el selector y `start-besu.sh`, scripts Hardhat en `pruebas/`. |
| 6 | [Fase 1.6: notificaciones de rechazo](./06-fase-1.6-notificaciones.md) | Métodos JSON-RPC custom (`gasMembership_*`) para que el cliente sepa por qué/cuándo se rechazó una TX. `RejectionEventBus`, el gotcha del namespace en `rpc-http-api`, script de saturación per-block. |
| 7 | [Fase 2: cuota mensual + bloqueo](./07-fase-2-cuota-mensual.md) | Enforcement mensual: `UsageMeter.sol`, contador híbrido in-memory + commit on-chain, `UsageBlockListener`, bloqueo de 5 min, nodo recorder que firma `recordUsageBatch`, RPC `gasMembership_getMonthlyUsage`. |

---

## Descripción general

### Contexto

La red es una **CBDC privada** corriendo Hyperledger Besu 25.8.0 con **QBFT** y `baseFeePerGas = 0` (zero-gas). Sin un precio de gas como freno natural, hace falta otro mecanismo para evitar que una sola cuenta sature los bloques y para monetizar el servicio según planes pagos.

El plugin implementa **enforcement por tier**:

- **BASIC, STANDARD, PREMIUM** — planes pagos con cuota mensual escalada (10× entre niveles).
- **WHITELISTED** — cuentas operativas (relay/admin) sin cuota.
- **NONE** — cuentas sin membresía: rechazadas estrictamente.

El tier por cuenta vive on-chain en un contrato `MembershipRegistry`. El plugin lo consulta vía `eth_call` interno y aplica la cuota correspondiente en cada TX que evalúa.

### Roadmap

| Fase | Estado | Qué hace | Mecanismo | Punto |
|---|---|---|---|---|
| **Fase 1** | **Cerrada** (commit `442195b`) | Enforce cuota por bloque por tier | `PluginTransactionSelector` | Al armar bloque |
| **Fase 1.5** | **Cerrada** (2026-05-27) | Rechazo upfront de casos imposibles (NONE, `txGasLimit > tierQuota`) | `PluginTransactionPoolValidator` | Al `eth_sendRawTransaction` |
| **Fase 1.6** | **Cerrada** (2026-05-28) | Notifica al cliente el motivo/bloque del rechazo | `RpcEndpointService` (`gasMembership_*`) | Polling del cliente |
| **Fase 2** | **Cerrada** (2026-05-31) | Cuota mensual on-chain + bloqueo 5 min | `UsageMeter.sol` + `BlockAddedListener` + nodo recorder | Bloqueo cuenta 5 min |

Detalle de la diferencia: la cuota por bloque protege la red en tiempo real (anti-spam, fairness). La cuota mensual hace cumplir el SLA del plan pago. Son problemas distintos y se resuelven con mecanismos distintos. Ver [§ Roadmap en requisitos](./01-requisitos-y-logica.md#estrategia-en-dos-fases) y [Fase 1.5 detallada](./05-fase-1.5-validator-y-pruebas.md).

---

## Estructura del repo

```
plugins-gas/
├── plugin/         ← Plugin Java (Gradle, Java 21, Besu 25.8.0 plugin-api) — selector + validator
├── contracts/      ← Proyecto Foundry (Solidity 0.8.20 + OpenZeppelin 5.1.0)
├── node/           ← Nodo Besu local QBFT (chainId 650540) + smoke test
├── pruebas/        ← Proyecto Hardhat + ethers v6 con scripts deploy:basic / deploy:exceed
├── docs/           ← Esta documentación
├── CLAUDE.md       ← Resumen para futuras sesiones de Claude Code
└── .gitignore
```

Lecturas recomendadas según rol:

- **Onboarding nuevo en el equipo** → leer `docs/README.md` → `01-requisitos-y-logica.md` → `02-arquitectura.md`.
- **Backend dev que va a tocar el plugin** → `02-arquitectura.md` + `04-smoke-test-e2e.md`.
- **Solidity dev / auditor del contrato** → `03-contratos.md` + las decisiones de §5 del doc de requisitos.
- **DevOps / SRE preparando deployment** → `04-smoke-test-e2e.md` + `node/start-besu.sh`.

---

## Quick start

Requisitos previos (macOS):

- JDK 21 (Homebrew): `brew install openjdk@21`
- Foundry: `curl -L https://foundry.paradigm.xyz | bash && foundryup`
- Besu 25.8.0 con soporte Falcon-512 en `/Users/edumar111/tools/besu-25.8.0-falcon/`
- Las deps de Foundry no están commiteadas — instalá una vez:
  ```bash
  cd contracts && \
    forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 \
                  foundry-rs/forge-std --no-commit
  ```

Verificar que todo compila y testea:

```bash
# Java unit tests (168 tests: Fase 1 + validator + cache + tracker + bus + plugin + Fase 2 usage)
cd plugin && ./gradlew test

# Solidity tests (37 tests: 18 MembershipRegistry + 4 GasBurner + 15 UsageMeter)
cd contracts && forge test

# Smoke test end-to-end con cast/forge (arranca/detiene Besu dos veces, ~3 min total)
./node/smoke-test.sh

# Scripts de prueba con Hardhat + ethers v6 (positivo, negativo, saturación per-block)
cd pruebas && npm install && npm run deploy:basic && npm run deploy:exceed && npm run deploy:saturate
```

Detalle de cada paso en [`04-smoke-test-e2e.md`](./04-smoke-test-e2e.md).

---

## Cuotas de un vistazo

| Tier | Por bloque (Fase 1, enforced) | Por mes (Fase 2, enforced) |
|---|---|---|
| `NONE` | TX rechazada | TX rechazada |
| `BASIC` | 500K gas | 648 G gas (6.48 × 10¹¹) |
| `STANDARD` | 5M gas | 6.48 T gas (6.48 × 10¹²) |
| `PREMIUM` | 10M + sobrante | 12.96 T gas mínimo |
| `WHITELISTED` | sin límite | sin límite |

Con `block period = 2s` y mes de 30 días → 1,296,000 bloques/mes. Block gas limit de la red: **350M gas**.

---

## Convenciones de la documentación

- **Tiers** se escriben en mayúsculas (`BASIC`, `WHITELISTED`).
- **G = giga (10⁹), T = tera (10¹²)** — todas las cantidades de gas son *gas units*, no wei.
- Cuando un valor tiene una fuente verificable (`genesis.json`, una decisión de producto, un test que lo cubre), se indica explícitamente.
- Decisiones de producto cerradas se listan en [§ 12 del doc de requisitos](./01-requisitos-y-logica.md#decisiones-cerradas) — si cambian, ese doc se actualiza primero.
