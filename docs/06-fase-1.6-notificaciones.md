# 06 — Fase 1.6: Notificaciones de rechazo vía JSON-RPC custom

> Cómo el cliente averigua por qué y en qué bloque el plugin rechazó una TX,
> incluido el caso que la Fase 1.5 no cubre (rechazo per-block `invalidTransient`).
> Implementa los **niveles 1 y 2** del diseño (polling sobre métodos RPC custom);
> el nivel 3 (push real-time vía WebSocket propio) quedó fuera de scope.

---

## 1. Qué resuelve

El validator de Fase 1.5 rechaza al submit los casos imposibles (NONE,
`txGasLimit > tierQuota`) — el cliente recibe error directo de
`eth_sendRawTransaction`. Pero hay un caso que **solo el selector** puede
detectar, al armar el bloque:

```
ALICE (BASIC, cuota 500K) manda dos consumeGas: 350K (nonce N) y 300K (nonce N+1).
  Bloque M:   TX-A entra (used→~371K). TX-B proyecta 371K+400K > 500K → invalidTransient.
  Bloque M+1: contador reseteado → TX-B entra.
```

TX-B no se descarta — se difiere un bloque. Sin notificación, el cliente solo ve
que TX-B tarda un bloque más, sin saber por qué. Fase 1.6 expone ese motivo.

## 2. Mecanismo: `RpcEndpointService` (no servidor propio)

El plugin registra dos métodos JSON-RPC custom usando el
`RpcEndpointService` del plugin-api de Besu. **No abre puertos ni embebe
servidores** — los métodos quedan disponibles automáticamente en los transports
que Besu ya tiene: HTTP (`4545`) y WebSocket (`4546`).

- **Nivel 1** — cliente pollea por HTTP. Funciona pero abre TCP por request.
- **Nivel 2** — cliente pollea reusando una conexión WS persistente. ~20-50× más
  rápido por request. Mismo método, mismo server-side; el cliente elige transport.

Push real-time (nivel 3) requeriría un WS server propio porque Besu no permite
registrar tipos de `eth_subscribe` desde un plugin. No está implementado.

## 3. ⚠️ Gotcha: habilitar el namespace en `rpc-http-api`

Besu **no expone** métodos de plugin cuyo namespace no esté en la lista de APIs
habilitadas. Hay que agregar `GASMEMBERSHIP` a `rpc-http-api` y `rpc-ws-api` en
`node/config.toml`:

```toml
rpc-http-api=["ADMIN","ETH","NET","WEB3","QBFT","TXPOOL","PERM","TRACE","GASMEMBERSHIP"]
rpc-ws-api=["ADMIN","ETH","NET","WEB3","QBFT","TXPOOL","PERM","TRACE","GASMEMBERSHIP"]
```

El match es **case-insensitive por prefijo** (`RpcEndpointServiceImpl.hasNamespace`
hace `methodName.toUpperCase().startsWith(apiName.toUpperCase())`), así que
`GASMEMBERSHIP` cubre `gasMembership_getRejection`. Sin esta línea, el cliente
recibe `Method not found` aunque el plugin haya registrado los métodos.

## 4. API contract

### `gasMembership_getRejection(txHash)`

| Param | Tipo | Descripción |
|---|---|---|
| `txHash` | string `0x` + 64 hex | Hash de la TX |

Devuelve el último rechazo registrado de esa TX, o `null` si no hubo (o expiró
del cache de 5 min):

```jsonc
{
  "txHash": "0x75dd...",
  "sender": "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
  "tier": "BASIC",
  "reason": "excedió límite de gas en el bloque",
  "blockNumber": 1234,        // 0 si vino del validator
  "txGasLimit": 400000,
  "usedInBlock": 371000,      // 0 si vino del validator
  "quota": 500000,
  "timestamp": 1716000000000,
  "source": "SELECTOR"        // o "VALIDATOR"
}
```

### `gasMembership_listRejectionsBySender(sender, limit?)`

| Param | Tipo | Descripción |
|---|---|---|
| `sender` | string `0x` + 40 hex | Address del sender |
| `limit` | int (opcional) | Default 20, clamp a [1, 100] |

Devuelve un array de rechazos del sender ordenados desc por `timestamp`. Vacío si
no hay registros vigentes.

### Errores

`txHash`/`sender` inválidos → JSON-RPC error `-32602` (invalid params) con mensaje.

### Uso desde ethers v6

```ts
// HTTP (JsonRpcProvider) o WS (WebSocketProvider) — mismo método.
const rej = await provider.send("gasMembership_getRejection", [txHash]);
if (rej) console.log(`Rechazada en bloque ${rej.blockNumber}: ${rej.reason}`);
```

## 5. Componentes Java

| Clase | Rol |
|---|---|
| `events/RejectionEvent` | Record inmutable del rechazo (incluye `Source` enum: VALIDATOR / SELECTOR). |
| `events/RejectionEventBus` | Cache en memoria. `LinkedHashMap` acotado (cap 10K) + TTL 5 min chequeado en lectura. Thread-safe (todo bajo `synchronized`). Sin dependencias externas. |

**Por qué `LinkedHashMap` y no Caffeine**: las deps de Besu son `compileOnly`, así
que una Caffeine transitiva no está en el classpath de compilación, y agregarla
explícitamente la shadwearía al JAR con riesgo de conflicto. El cache propio es
trivial para los requisitos (TTL + cap).

**Integración**:

- `GasMembershipTransactionSelector` y `GasMembershipTransactionValidator` reciben
  el bus por constructor y llaman `eventBus.emit(...)` en cada rama de rechazo. Las
  decisiones (SELECTED / invalid / invalidTransient / Optional) **no cambian** — el
  emit es un side-effect aditivo.
- `GasMembershipPlugin` crea el bus singleton, lo inyecta en ambos factories
  (selector y validator comparten la misma instancia) y registra los 2 métodos RPC.

**Espejado del validator**: los rechazos del validator también van al bus
(`source=VALIDATOR`, `blockNumber=0`), así una sola API sirve para rechazos al
submit y en runtime.

## 6. Storage: TTL y cap

- TTL **5 min**: suficiente para que un cliente que pollea reconecte si se cae.
- Cap **10K** entries: bound de memoria. Con ~30 TX/s y 5% rechazos son ~450
  entries/5min — 10K es 20× margen.
- **No persiste**. Si Besu reinicia, el log se pierde. Audit trail de largo plazo
  (Postgres/Redis) sería Fase 3.
- Solo el **último** rechazo por txHash. Una TX rechazada en N bloques consecutivos
  por `invalidTransient` sobrescribe; el cliente que pollea ve cada actualización.

## 7. Script de validación

`pruebas/scripts/deploy-multi-tx-saturate-block.ts` (`npm run deploy:saturate`):

1. Verifica que ALICE es BASIC.
2. Deploya `GasBurner` (de `contracts/`) con el owner (WHITELISTED, sin cuota). Reusa vía `.gasburner-address`.
3. Manda dos `consumeGas` con nonces consecutivos: 350K (nonce N) y 300K (nonce N+1).
4. Pollea `gasMembership_getRejection(TX-B)` para capturar el rechazo per-block.
5. Reporta en qué bloque entró cada TX y el gap (TX-B debería entrar 1 bloque después).

**Por qué GasBurner y no transferencias**: el accounting per-block usa gas REAL
consumido (post-processing), no el gasLimit declarado. Una transferencia usa solo
21K real y no dispararía la cuota. Detalle en
[`03-contratos.md § GasBurner`](./03-contratos.md).

## 8. Tests

De 69 a **82 tests Java** (0 fallos):

| Suite | Tests nuevos | Foco |
|---|---|---|
| `RejectionEventBusTest` | 7 | roundtrip, sobrescritura por hash, filtro+orden por sender, clamp de limit, expiración TTL (clock fake), cap por inserción |
| `GasMembershipTransactionSelectorTest` | +3 | emite en per-block y permanente; SELECTED no emite |
| `GasMembershipTransactionValidatorTest` | +2 | emite con `source=VALIDATOR`; TX válida no emite |
| `GasMembershipPluginTest` | +1 | falla si falta `RpcEndpointService`; verifica registro de los 2 métodos |

## 9. Limitaciones y siguiente paso

- **Polling, no push**: el cliente consulta cada ~2s (block-time). Para
  notificación < 100ms (nivel 3) haría falta un WS server propio. No implementado.
- **El cache es efímero** (5 min, sin persistencia).
- El caso `invalidTransient` se re-emite cada bloque que la TX sigue sin entrar; el
  cliente ve el último estado, no el historial completo por bloque.
