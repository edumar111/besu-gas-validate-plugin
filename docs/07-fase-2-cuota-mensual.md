# 07 — Fase 2: Cuota mensual on-chain + bloqueo de 5 min

> El **qué** y el **cómo** del enforcement mensual. Complementa la cuota por bloque de Fase 1: la
> per-block protege la red en tiempo real (anti-spam, fairness); la mensual hace cumplir el SLA del
> plan pago. Ver [requisitos §3.2 y §8](./01-requisitos-y-logica.md) y [arquitectura](./02-arquitectura.md).

---

## 1. Resumen de decisiones de producto

| Decisión | Elección | Por qué |
|---|---|---|
| Accounting | **Híbrido**: contador in-memory para enforcement + commit batch on-chain | El in-memory da enforcement sin un `eth_call` por TX; el on-chain da persistencia y auditoría para billing. |
| Período | **Mes calendario UTC** (`periodId = year*12 + month0`) | Alinea con ciclos de facturación; reset global el día 1 a las 00:00 UTC. |
| Fuente del uso | **`gasUsed` real** (delta de `cumulativeGasUsed` entre receipts) | Lo que realmente se consumió, no el `gasLimit` declarado. |
| Cuotas mensuales | **En config del plugin** (no on-chain) | Una sola fuente de verdad para las cuotas, igual que las per-block de Fase 1. El `UsageMeter` solo guarda consumo. |
| Committer | **Nodo designado** (`ratelimit.usage.recorder=true`) | En una red multi-validator, evita N TXs duplicadas por batch. La clave firmante vive en un solo nodo. |
| Cadencia commit | **Cada N bloques** (`ratelimit.usage.commitEveryBlocks`, default 150 ≈ 5 min) | Balance entre frescura de la persistencia y cantidad de TXs. |
| Bloqueo | **In-memory, TTL 5 min, todas las cuentas salvo WHITELISTED** | Penalización corta; perderla en un restart es aceptable (se re-evaluaría igual). |

---

## 2. Por qué dos dimensiones de cuota

- **Solo per-block** dejaría que una cuenta consuma toda su quota cada bloque durante 30 días sin
  pagar más — sostenible para la red, pero no monetizable.
- **Solo per-month** dejaría que una cuenta PREMIUM con cuota mensual gigante envíe 350M (todo el
  block gas limit) en un solo bloque — tumbando la inclusión de los demás tiers ese bloque.

Por eso ambas dimensiones coexisten. El enforcement mensual corre **antes** que el per-block en
selector y validator.

---

## 3. Cuotas mensuales

Con `block period = 2s` y mes de 30 días → **1,296,000 bloques/mes**.

| Tier | Por bloque | Por mes (default) | Property |
|---|---|---|---|
| BASIC | 500 K | **648 G** (6.48 × 10¹¹) | `ratelimit.gas.monthly.basic` |
| STANDARD | 5 M | **6.48 T** (6.48 × 10¹²) | `ratelimit.gas.monthly.standard` |
| PREMIUM | 10 M + sobrante | **12.96 T** (piso) | `ratelimit.gas.monthly.premium` |
| WHITELISTED | sin límite | sin límite (`Long.MAX_VALUE`) | — |
| NONE | rechazada | rechazada | — |

El piso mensual de PREMIUM es fijo (12.96 T). El "sobrante elástico" es solo per-block; no aplica
al cómputo mensual.

---

## 4. Comportamiento ante exceso mensual

| Violación | Comportamiento | Penalización | Razón (`REASON_*`) |
|---|---|---|---|
| `usoMensual + txGasLimit > cuotaMensual` | TX rechazada + cuenta **bloqueada 5 min** | Sí — todas las TX de la cuenta se rechazan durante la ventana | `monthly gas quota exceeded` |
| Cuenta dentro de la ventana de bloqueo | TX rechazada (cualquier tier salvo WHITELISTED) | — | `account temporarily blocked: monthly gas quota exceeded` |

A diferencia de los rechazos permanentes de Fase 1 (NONE, `txGasLimit > tierQuota`), el rechazo
mensual es **transitorio** (`invalidTransient` en el selector): el bloqueo expira en ~5 min y la
cuota se resetea el mes siguiente, así que la TX puede reintentarse — no se descarta del pool.

---

## 5. Arquitectura

```
                         ┌──────────────── TODOS los nodos ────────────────┐
   bloque añadido ──────►│ UsageBlockListener (BlockAddedListener)          │
                         │  ├── PeriodClock.periodId(header.timestamp)      │
                         │  ├── MonthlyUsageTracker.rollTo(periodId)        │
                         │  ├── por cada TX: gasUsed = Δ cumulativeGasUsed  │
                         │  │      → tracker.addUsage(sender, gasUsed)      │
                         │  ├── si cumulative > cuotaMensual →              │
                         │  │      BlockedAddressRegistry.block(sender)     │
                         │  └── cada N bloques → CommitTrigger.onCommitDue  │
                         └───────────────────────┬─────────────────────────┘
                                                 │ (solo en el recorder)
                                                 ▼
                         ┌──────────── nodo RECORDER ───────────┐
                         │ UsageCommitter                        │
                         │  ├── snapshotPending() del tracker     │
                         │  ├── RecordUsageTxEncoder.encodeCallData (recordUsageBatch)
                         │  ├── pendingNonce (eth_getTransactionCount)
                         │  ├── Secp256k1RecorderSigner.sign(keccak(unsignedTx))
                         │  ├── eth_sendRawTransaction (JsonRpcRecorderRpc)
                         │  └── applyCommitted() → mueve pending a baseline
                         └───────────────────────┬───────────────┘
                                                 ▼
                                       UsageMeter.sol (on-chain)
                                         recordUsageBatch / getUsage


   eth_sendRawTransaction / armado de bloque
              │
              ├── GasMembershipTransactionValidator (al pool)   ┐
              └── GasMembershipTransactionSelector (al bloque)   ├─ comparten:
                       │                                          │   MonthlyQuotaGuard
                       └── MonthlyQuotaGuard.check(sender,tier,gasLimit)
                              ├── WHITELISTED            → OK
                              ├── isBlocked(sender)      → BLOCKED  → reject
                              ├── cumulative+gas > quota → EXCEEDED → reject + block(sender)
                              └── resto                  → OK (sigue enforcement per-block)
                                       │
                                       └── baseline rehidratado lazy desde
                                           UsageMeterClient.getUsage (eth_call)
```

### Componentes Java nuevos (`plugin/.../usage/`)

| Componente | Rol |
|---|---|
| `PeriodClock` | `periodId(epochSeconds)` = `year*12 + month0` (UTC). Sobre el timestamp del bloque → consenso-consistente. |
| `MonthlyUsageTracker` | Por `(periodId, cuenta)`: `baseline` (rehidratado on-chain) + `pendingDelta` (in-memory). Anti-stampede en la carga del baseline. Rollover al cambiar de mes. |
| `BlockedAddressRegistry` | `cuenta → expiry`, TTL 5 min, in-memory, thread-safe, expiración lazy. |
| `MonthlyQuotaGuard` | Punto único de decisión `OK/BLOCKED/EXCEEDED`, compartido por selector y validator. |
| `UsageBlockListener` | `BlockAddedListener` (corre en todos los nodos): cuenta uso real + dispara bloqueo + dispara commits. |
| `UsageMeterClient` | `eth_call getUsage(periodId,account)` para rehidratar el baseline. Selector `0x44202d6e`. |
| `UsageCommitter` | (solo recorder) arma/firma/envía `recordUsageBatch`. Selector `0x1e5092f1`. |
| `RecordUsageTxEncoder` + `Rlp` | Calldata ABI + RLP de la TX legacy EIP-155 (puro, testeable con vectores). |
| `Secp256k1RecorderSigner` + `RecorderSigner` | Firma secp256k1 con `besu-crypto` (compileOnly, no se bundlea). |
| `JsonRpcRecorderRpc` + `RecorderRpc` | `eth_getTransactionCount` + `eth_sendRawTransaction` con el `HttpClient` del JDK. |
| `TierResolver` / `CommitTrigger` | Interfaces de desacople (lookup de tier compartido / disparo de commit). |

### Contrato nuevo: `UsageMeter.sol`

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

- `recordUsageBatch` **acumula** deltas. El recorder envía solo el delta no commiteado desde el
  último flush (lleva su propio baseline → reenvíos no inflan).
- `Ownable` + rol `recorder` separado del owner.
- **El recorder debe estar `WHITELISTED`** en el `MembershipRegistry`, o sus propias TX
  `recordUsageBatch` serían rechazadas por este mismo plugin.

---

## 6. El modelo híbrido baseline + pending

Para cada cuenta en el período actual:

```
cumulative = committedBaseline + pendingDelta
```

- **committedBaseline** — uso ya persistido on-chain. Se rehidrata lazy desde `UsageMeter.getUsage`
  la primera vez que se toca la cuenta en el período. Tras un restart del nodo, recupera el uso real.
- **pendingDelta** — uso visto en bloques desde el arranque/último commit, aún no persistido.

El `UsageCommitter` toma un `snapshotPending()`, envía la TX, y al confirmarse llama
`applyCommitted(snapshot)` que mueve **exactamente el delta enviado** de pending a baseline. El uso
que llegó entre el snapshot y la confirmación queda en pending para el próximo commit (no se pierde
ni se duplica).

**Pérdida máxima ante restart**: acotada a la cadencia de commit (~5 min). El baseline se rehidrata
de la cadena al arrancar.

---

## 7. Configuración

```properties
# === Cuota mensual (Fase 2) — gas units ===
ratelimit.gas.monthly.basic=648000000000
ratelimit.gas.monthly.standard=6480000000000
ratelimit.gas.monthly.premium=12960000000000

# Duración del bloqueo por exceso mensual (segundos). Default 300 (5 min ≈ 150 bloques).
ratelimit.monthlyBlockDurationSeconds=300

# Cadencia de commit del uso al UsageMeter (bloques). Default 150.
ratelimit.usage.commitEveryBlocks=150

# Dirección del UsageMeter on-chain. Si falta, el enforcement mensual corre solo in-memory
# (sin persistencia ni rehidratación tras restart).
ratelimit.usage.meterContract=0xUSAGE_METER_ADDRESS

# === Solo en el nodo RECORDER ===
ratelimit.usage.recorder=true
# Clave privada del recorder (64 hex). OBLIGATORIA si recorder=true.
ratelimit.usage.recorderKey=0xRECORDER_PRIVATE_KEY
# RPC local para eth_sendRawTransaction. OBLIGATORIO si recorder=true.
ratelimit.nodeUrl=http://localhost:4545
# Gas límite de la TX recordUsageBatch (default 8M).
ratelimit.usage.recorderGasLimit=8000000
```

**Validación eager** (en el constructor del config): si `recorder=true`, exige `recorderKey`
(64 hex), `meterContract` y `nodeUrl` presentes; de lo contrario el plugin crashea visible al
arrancar.

**Degradación elegante**: si `BesuEvents` no está disponible en el runtime, Fase 2 queda
**desactivada** (warning en logs) y el plugin opera solo con enforcement per-block. No crashea.

**Namespace RPC**: igual que Fase 1.6, `GASMEMBERSHIP` debe estar en `rpc-http-api`/`rpc-ws-api`
de `config.toml` para exponer `gasMembership_getMonthlyUsage`.

---

## 8. Método JSON-RPC nuevo

```
gasMembership_getMonthlyUsage(account) → {
  account, periodId, period (YYYY-MM), used, blocked, blockedUntil
}
```

Devuelve `null` si Fase 2 no está activa en el nodo. Útil para clientes y para reconciliación de
billing contra el `UsageMeter` on-chain.

---

## 9. Selectores cross-stack (tripwires)

Igual que `getTier` en Fase 1, los selectores ABI que el plugin hardcodea están pineados por tests
Solidity en `UsageMeter.t.sol`. Si la firma del contrato cambia, el test falla y avisa que hay que
actualizar el cliente Java.

| Función | Selector | Hardcoded en | Test |
|---|---|---|---|
| `getUsage(uint256,address)` | `0x44202d6e` | `UsageMeterClient` | `test_GetUsageSelectorMatchPluginHardcoded` |
| `recordUsageBatch(uint256,address[],uint256[])` | `0x1e5092f1` | `RecordUsageTxEncoder` | `test_RecordUsageBatchSelectorMatchPluginHardcoded` |

---

## 10. Tests

- **Solidity** (`UsageMeter.t.sol`, 15 tests): acumulación, aislamiento por período, `onlyRecorder`,
  `setRecorder`, length-mismatch, selectores cross-stack.
- **Java** (`usage/`, 83 tests nuevos): `PeriodClock` (bordes de mes UTC), `MonthlyUsageTracker`
  (baseline+delta, rollover, concurrencia), `BlockedAddressRegistry` (TTL), `UsageBlockListener`
  (conteo por delta de receipts, disparo de bloqueo, cadencia de commit), `MonthlyQuotaGuard` +
  integración en selector/validator, `Rlp` (vectores del Yellow Paper), `RecordUsageTxEncoder`
  (layout ABI + EIP-155 v), `Secp256k1RecorderSigner` (derivación de address + firma
  determinística), `UsageCommitter` (snapshot/aplicar/reintento), wiring del plugin.

**Total tras Fase 2**: 168 tests Java + 37 Solidity.

---

## 11. Deploy del UsageMeter

```bash
cd contracts
# RECORDER = dirección derivada de ratelimit.usage.recorderKey
OWNER=0xOWNER RECORDER=0xRECORDER forge script script/DeployUsageMeter.s.sol \
  --rpc-url local --private-key $PRIVATE_KEY --broadcast --legacy
```

Luego, en el `MembershipRegistry`, asignar `WHITELISTED` a la cuenta recorder para que sus
`recordUsageBatch` no sean rechazadas.

---

## 12. Pendientes / futuras mejoras

- **Seguridad de la clave recorder**: hoy vive en config de un nodo. A futuro se puede mover al
  `SecurityModule`/HSM (la plugin-api lo soporta).
- **Rollover de período + commit**: al cruzar el fin de mes, conviene forzar un commit del período
  saliente antes del `rollTo` para no perder el pending del mes viejo (hoy el commit periódico lo
  cubre si la cadencia es menor que la distancia al cierre; un commit explícito en el boundary sería
  más robusto).
- **Multisig** para el owner del `UsageMeter` en producción.
