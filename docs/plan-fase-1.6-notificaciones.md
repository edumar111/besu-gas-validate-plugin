# Plan: Fase 1.6 — Notificaciones de rechazo vía JSON-RPC custom

## Objetivo

Permitir que el cliente sepa rápido por qué motivo y en qué bloque el plugin
rechazó una TX, especialmente para el caso que la Fase 1.5 NO cubre: rechazo
per-block (`used + txGasLimit > tierQuota`) donde la TX queda en el pool y se
re-evalúa en cada bloque sin que el cliente se entere.

## Diferencia con Fase 1.5

| Caso | Fase 1.5 (validator) | Fase 1.6 (notificaciones) |
|---|---|---|
| `tier == NONE` | Rechazo al `eth_sendRawTransaction` (excepción inmediata) | Espejado al bus para auditoría unificada |
| `txGasLimit > tierQuota` (BASIC/STANDARD) | Rechazo al submit (excepción inmediata) | Espejado al bus |
| `used + txGasLimit > tierQuota` (`invalidTransient`) | **No detectable upfront**. TX queda en pool, cliente no se entera. | **Nuevo**: el selector lo escribe al bus; cliente pollea custom RPC y se entera por bloque. |
| PREMIUM no cabe en cuota + sobrante | No detectable upfront. | Idem: el selector lo escribe al bus. |

Resultado: una sola API para que el cliente sepa **cualquier** rechazo del
plugin, sea al submit o en runtime.

## Estrategia técnica

Usar `RpcEndpointService` del plugin-api de Besu para exponer dos métodos
JSON-RPC custom (`gasMembership_*`). Esos métodos quedan disponibles en
**ambos** transports que Besu ya tiene levantados (HTTP en port 4545, WS en
port 4546) — el plugin no abre puertos nuevos ni embebe servidores propios.

El cliente lee con polling (cada 2s, un block-time). Sobre HTTP funciona pero
abre TCP por request; sobre WS reutiliza la conexión y es ~20-50× más rápido.
El plugin no se entera de la diferencia.

**Push real-time queda fuera de scope.** Si en algún momento se necesita
(< 100ms latencia), se agrega encima como Fase 1.7 sin tocar el query path.

## Componentes nuevos

### 1. `model/RejectionEvent.java` — DTO inmutable

```java
public record RejectionEvent(
    Hash txHash,
    Address sender,
    Tier tier,
    String reason,         // REASON_NO_MEMBERSHIP, REASON_TX_EXCEEDS_TIER_QUOTA, REASON_BLOCK_QUOTA_EXCEEDED
    long blockNumber,      // bloque en el que se decidió el rechazo (0 si vino del validator, donde no hay block context)
    long txGasLimit,
    long usedInBlock,      // 0 si vino del validator
    long quota,
    Instant timestamp,
    Source source          // VALIDATOR | SELECTOR
) {
    public enum Source { VALIDATOR, SELECTOR }
}
```

### 2. `events/RejectionEventBus.java` — store + pub/sub

```java
public class RejectionEventBus {
    private final Cache<Hash, RejectionEvent> byHash;          // último rechazo por TX
    private final Set<Consumer<RejectionEvent>> subscribers;   // listeners in-process

    public void emit(RejectionEvent ev);                       // selector + validator llaman acá
    public Optional<RejectionEvent> get(Hash txHash);
    public List<RejectionEvent> listBySender(Address sender, int limit);
    public AutoCloseable subscribe(Consumer<RejectionEvent> sub); // para Fase 1.7 (push WS)
}
```

Implementación interna con [Caffeine](https://github.com/ben-manes/caffeine):

```java
this.byHash = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();
```

`listBySender` itera todos los valores del cache y filtra por sender. O(n) con
n ≤ 10K — despreciable. Si más adelante crece el cap, considerar un índice
secundario.

### 3. Modificaciones en componentes existentes

#### `GasMembershipTransactionSelector.java`
- Constructor: recibe `RejectionEventBus` adicional.
- En las dos ramas `invalidTransient` (BASIC/STANDARD per-block, PREMIUM sin sobrante): llamar `eventBus.emit(new RejectionEvent(...))` antes del return.
- En la rama `invalid` permanente (`txGasLimit > tierQuota`): también emitir (para auditoría).
- En la rama `tier == NONE`: también emitir.
- En `WHITELISTED` y `SELECTED`: NO emitir (eventos OK no se loguean).

#### `GasMembershipTransactionValidator.java`
- Constructor: recibe `RejectionEventBus` adicional.
- Antes de cada `return Optional.of(reason)`: emitir con `Source.VALIDATOR`, `blockNumber=0`, `usedInBlock=0`.

#### `GasMembershipPlugin.java`
- Crear el `RejectionEventBus` (singleton, como cache/tracker).
- Inyectarlo en ambos factories (selector y validator).
- Resolver `RpcEndpointService` del `ServiceManager`.
- Registrar dos métodos:
  ```java
  rpc.registerRPCEndpoint("gasMembership", "getRejection", this::handleGetRejection);
  rpc.registerRPCEndpoint("gasMembership", "listRejectionsBySender", this::handleListRejectionsBySender);
  ```

#### `GasMembershipTransactionSelectorFactory.java` y `GasMembershipTransactionValidatorFactory.java`
- Pasar el `eventBus` al constructor del selector/validator.

## API contract

### `gasMembership_getRejection(txHash)`

| Param | Tipo | Descripción |
|---|---|---|
| `txHash` | string `0x` + 64 hex | Hash de la TX |

**Devuelve**:

```jsonc
// Si hubo rechazo en los últimos 5 min:
{
  "txHash": "0xabc...",
  "sender": "0x3C44...",
  "tier": "BASIC",
  "reason": "excedió límite de gas en el bloque",
  "blockNumber": 1234,
  "txGasLimit": 200000,
  "usedInBlock": 400000,
  "quota": 500000,
  "timestamp": 1716000000000,
  "source": "SELECTOR"
}

// Si no hubo rechazo registrado (o expiró):
null
```

### `gasMembership_listRejectionsBySender(sender, limit)`

| Param | Tipo | Descripción |
|---|---|---|
| `sender` | string `0x` + 40 hex | Address del sender |
| `limit` | int | Máximo de eventos (clamp a 100) |

**Devuelve**: array de `RejectionEvent` ordenados desc por `timestamp`. Vacío si
no hay rechazos en cache para ese sender.

### Errores

- `txHash` inválido → JSON-RPC error `-32602 invalid params` con mensaje.
- `sender` inválido → idem.
- `limit < 1` → clamp a 1. `limit > 100` → clamp a 100. (No errores.)

## Storage

- **In-memory only**. Caffeine cache con TTL 5 min, cap 10K entries.
- **No persistencia en disco**. Si Besu reinicia, se pierde el log de rechazos. Para audit trail de largo plazo habría que agregar Postgres/Redis — fuera de scope.
- Por qué 5 min: tiempo suficiente para que un cliente que poll-ea reconecte si se cayó, sin acumular memoria indefinidamente.
- Por qué 10K cap: en una red con ~30 TX/s y 5% rechazos, son ~90 rechazos/min × 5 = 450 entries. 10K es 20× ese cap — generoso pero no excesivo.

## Tests

### Nuevos archivos

#### `events/RejectionEventBusTest.java` — ~6 tests
- `emit_almacenaPorHash`
- `get_devuelveOptionalEmptyParaHashDesconocido`
- `get_devuelveElEventoEmitido`
- `listBySender_filtraYOrdenaDescPorTimestamp`
- `listBySender_clampLimit`
- `cacheExpiraDespuesDeTTL` (usar `Ticker` testeable para no esperar 5 min real)

### Modificaciones a tests existentes

#### `GasMembershipTransactionSelectorTest.java`
- Inyectar un `RejectionEventBus` mock en el constructor.
- En 4 tests existentes (los que disparan rechazo), verificar que se llamó `eventBus.emit()` con el evento esperado.
- En los tests de `SELECTED`/`WHITELISTED`, verificar que NO se llamó `emit()`.

#### `GasMembershipTransactionValidatorTest.java`
- Idem: inyectar bus, verificar emit en cada rama de rechazo.

#### `GasMembershipPluginTest.java`
- Mock del `RpcEndpointService`.
- Verificar que `register()` invoca `rpc.registerRPCEndpoint(...)` dos veces con los nombres correctos.
- Verificar que el factory del selector y del validator reciben el mismo bus.
- Nuevo test: `register_fallaSiRpcEndpointServiceNoEstaDisponible`.

### Conteo esperado

| Suite | Antes | Después |
|---|---|---|
| Java (todos) | 69 | ~80 (+6 bus, +5 ajustes selector/validator/plugin) |
| Solidity | 22 | 22 (sin cambios) |

## Script de validación

### `pruebas/scripts/deploy-multi-tx-saturate-block.ts` (nuevo)

Reproduce el caso `invalidTransient` y verifica que el cliente puede saberlo
polleando el nuevo RPC custom.

Flujo:

1. Verificar que `BASIC_PK` está como BASIC (igual que los scripts existentes).
2. Enviar TX-A de 250K gas desde ALICE (cabe holgada en BASIC 500K). Esperar
   recibo. used = 250K.
3. Enviar TX-B de 250K gas desde ALICE (cabe en el mismo bloque, 500K total).
   Esperar recibo o ver que se programa para el bloque siguiente.
4. **Enviar TX-C de 250K gas inmediatamente**. En algún bloque, esta TX va a
   ser evaluada con `used = 500K` (o cercano) y `projected = 750K > quota`.
   Resultado del selector: `invalidTransient`.
5. **Loop**: cada 2s, llamar `provider.send("gasMembership_getRejection",
   [txC.hash])`. Cuando devuelva un evento, mostrarlo.
6. Cuando aparezca el evento de rechazo, mostrar el output esperado y terminar.

```
Resultado: TX RECHAZADA POR EL SELECTOR (esperado)
===========================================
Hash:             0x...
Bloque:           1234
Sender:           0x3C44... (BASIC)
Razón:            excedió límite de gas en el bloque
Gas usado bloque: 500 000 / 500 000 (100%)
Gas TX:           250 000
Detectado en:    2 polls (4.3s desde submit)
```

Eventualmente la TX-C se mina (en el bloque siguiente cuando used se resetea).
El script puede mostrar también el receipt final.

### Comando

```bash
cd pruebas && npm run deploy:saturate
```

Agregar `predeploy:saturate` y `deploy:saturate` al `package.json`.

## Pasos de implementación

Ordenados, con dependencias entre sí:

1. **`RejectionEvent` record** + sus tests (no hay nada que romper, file nuevo).
2. **`RejectionEventBus`** + tests (6 tests independientes).
3. **Modificar selector** — agregar bus al constructor + emit en las 4 ramas de rechazo. Tests del selector ajustados.
4. **Modificar selector factory** — pasar el bus.
5. **Modificar validator** — agregar bus al constructor + emit en las 2 ramas de rechazo. Tests del validator ajustados.
6. **Modificar validator factory** — pasar el bus.
7. **Modificar plugin** — crear bus singleton, inyectar en ambos factories, registrar 2 RPC methods. Tests del plugin ajustados.
8. **Correr suite Java entera** — debería pasar ~80 tests.
9. **Recompilar JAR**: `./gradlew build`.
10. **Reemplazar JAR en Besu**, reiniciar.
11. **Script de validación** `deploy-multi-tx-saturate-block.ts` + scripts del package.json.
12. **Documentación**:
    - Crear `docs/06-fase-1.6-notificaciones.md` con el detalle final.
    - Actualizar `docs/README.md` (índice + roadmap).
    - Actualizar `docs/01-requisitos-y-logica.md` (tabla de comportamientos).
    - Actualizar `docs/02-arquitectura.md` (componente nuevo + flujo).
    - Actualizar `CLAUDE.md` (mención corta).
    - Actualizar `pruebas/README.md` (script nuevo + RPC methods).

## Decisiones de diseño ya tomadas

1. **Espejar también los rechazos del validator** en el bus. Así una sola API
   `gasMembership_getRejection(hash)` sirve si el rechazo fue al submit
   (validator, blockNumber=0, source=VALIDATOR) o en runtime (selector,
   blockNumber=N, source=SELECTOR). Reduce ambigüedad para el cliente.
2. **Guardar solo el ÚLTIMO rechazo por txHash**. La misma TX puede ser
   rechazada en N bloques consecutivos por `invalidTransient` — guardar todos
   sería ruido. Si el cliente pollea, va a recibir cada actualización (mismo
   txHash pero distinto blockNumber/timestamp), lo cual es suficiente.
3. **`listBySender` itera todos los valores** del cache (O(n)). Cap es 10K
   → trivial. Si crece, agregamos índice secundario.
4. **No agrego `RpcEndpointService` como dependencia opcional**: si Besu no lo
   provee, falla en `register()`. Coherente con cómo manejamos los demás
   servicios.
5. **Caffeine como dependencia**: ya está en transitive de Besu, no agrega
   peso real al JAR.
6. **Sin auth/CORS específico**: hereda lo que Besu tenga configurado en
   `config.toml`. Para producción habría que definir, pero queda fuera.

## Riesgos / cosas a verificar durante implementación

- **El `RpcEndpointService` debe llamarse durante `register()`, no en `start()`** — está documentado en su javadoc. Si se cambia, falla en arranque.
- **Caffeine como dep transitiva de Besu**: confirmar la versión disponible. Si no está, agregarla explícitamente en `build.gradle` con `implementation` (no `compileOnly`).
- **Mismo bus en ambos factories**: bug fácil de cometer pasar instancias diferentes. Test del plugin debe verificar identidad de referencia.
- **Concurrency del bus**: el cache de Caffeine es thread-safe; el `Set<Consumer>` debe ser `CopyOnWriteArraySet` para iteración segura mientras se hace subscribe/unsubscribe.

## Lo que NO se toca

- El comportamiento del selector ni del validator cambia. Solo se le agrega el side-effect de emitir al bus. Las decisiones (SELECTED / invalid / invalidTransient / Optional.empty()) son idénticas a Fase 1.5.
- Las constantes de razones (`REASON_*`) no cambian.
- El config (`GasMembershipConfig`) no cambia. Sin properties nuevas.
- El smoke test (`node/smoke-test.sh`) no cambia.
- Los contratos (`MembershipRegistry`, `GasBurner`) no cambian.
- `start-besu.sh` no cambia.

## Esfuerzo estimado

| Tarea | Esfuerzo |
|---|---|
| `RejectionEvent` + `RejectionEventBus` + 6 tests | ~2h |
| Modificar selector + validator + factories + ajustes a tests existentes | ~1.5h |
| Modificar plugin (RPC registration) + test del plugin | ~1h |
| Script TS `deploy-multi-tx-saturate-block.ts` + integración con package.json | ~1.5h |
| Documentación (doc 06 nuevo + updates a 01/02/README/CLAUDE/pruebas) | ~1.5h |
| **Total** | **~7-8h** |
