# Plan: Fase 1.5 — validator que rechaza al submit

## Objetivo

Que `eth_sendRawTransaction` devuelva error directo cuando el plugin sabe que la
TX nunca va a poder ser incluida. Así el cliente recibe excepción en milisegundos
y se elimina el polling/timeout del script `deploy:exceed`.

## Diferencia con el selector actual

| Componente | Cuándo corre | Qué decide |
|---|---|---|
| `PluginTransactionSelector` (ya existe) | Al construir cada bloque | Si la TX entra a este bloque específico (necesita contexto: gas usado por sender en el bloque actual, leftover del bloque, etc.). |
| `PluginTransactionValidator` (nuevo) | Al **admitir al txpool** | Si la TX puede ser válida **alguna vez** (sin contexto de bloque). |

El validator solo rechaza lo que es **estructuralmente imposible**, no lo que
depende del estado del bloque.

## Lógica del validator

| Caso | Decisión | Razón |
|---|---|---|
| `tier == WHITELISTED` | válida | Pasa siempre. |
| `tier == NONE` | inválida (permanente) | `sender has no active membership` — igual que el selector. |
| `tier == PREMIUM` | válida | El selector decide con el sobrante del bloque; no se puede saber upfront. |
| `tier == BASIC/STANDARD` + `txGasLimit > tierQuota` | inválida (permanente) | `tx gasLimit exceeds tier quota` — igual que el selector. |
| Resto (BASIC/STANDARD con `txGasLimit ≤ tierQuota`) | válida | El selector se encarga del `invalidTransient` per-block. |

**Reuso de constantes**: `REASON_NO_MEMBERSHIP` y `REASON_TX_EXCEEDS_TIER_QUOTA`
ya existen en el selector. Las extraigo a una clase pública compartida
(`GasMembershipRejectionReasons`) o las hago referenciar del selector. Voto por
extraerlas para no acoplar validator → selector.

## Pasos

1. **Investigar la API de Besu** — verificar nombre exacto del interface
   (`PluginTransactionValidator` o similar), el servicio
   (`BesuTransactionValidatorService`), y si hace falta un factory como el
   selector. Búsqueda en los paquetes Besu disponibles antes de codear.

2. **Crear `GasMembershipTransactionValidator.java`** en un paquete `validator/`
   paralelo a `selector/`:
   - Mismas dependencias que el selector (`TierCache`, `MembershipContractClient`, `GasMembershipConfig`).
   - **Comparte la misma instancia de `TierCache`** con el selector — no quiero doble lookup. La cache se key-ea por `(sender, chainHeadBlock)`.
   - El lookup usa el chain head del momento (vía `BlockchainService` o similar — depende de qué expone Besu al validator).

3. **(Si hace falta) `GasMembershipValidatorFactory.java`** — factory pattern
   para que Besu pueda crear instancias por txpool slot.

4. **Modificar `GasMembershipPlugin.java`** — registrar el nuevo validator/factory
   con el `BesuTransactionValidatorService`. Manteniendo el selector existente
   sin cambios.

5. **Crear `GasMembershipTransactionValidatorTest.java`** con cobertura:
   - `WHITELISTED` → válida
   - `NONE` → inválida (permanente) con `REASON_NO_MEMBERSHIP`
   - `BASIC` + `gasLimit > 500K` → inválida (permanente) con `REASON_TX_EXCEEDS_TIER_QUOTA`
   - `BASIC` + `gasLimit ≤ 500K` → válida (deja pasar al selector)
   - `PREMIUM` (cualquier `gasLimit`) → válida
   - Cache hit: dos llamadas consecutivas del mismo sender → un solo lookup

6. **Correr la suite Java entera** — los 61 tests existentes deben seguir verdes.
   Los nuevos suman ~6.

7. **Recompilar el plugin**: `cd plugin && ./gradlew build`. El JAR shadowed
   nuevo va a `plugin/build/libs/`.

8. **Simplificar `pruebas/scripts/deploy-exceeding-basic-limit.ts`**: con el
   validator activo, `factory.deploy()` tira excepción inmediatamente. Reemplazo
   todo el polling por un `try/catch` simple:

   ```ts
   try {
     await FatStorageFactory.deploy({ gasLimit: TX_GAS_LIMIT });
     // INESPERADO
   } catch (e) {
     // ESPERADO: validator rechazó
     printRejected("rechazada al submit (validator)", ...);
   }
   ```

   Sin loop, sin timeout, sin sleep. ~30 líneas menos de código.

9. **Actualizar `pruebas/README.md`** — reescribir la sección del caso negativo:
   "El validator rechaza al admitir → `factory.deploy()` tira excepción en ~10ms
   → no hay timeout ni polling."

10. **Actualizar `CLAUDE.md`** — agregar línea nueva en arquitectura mencionando
    el validator. Mantener corto, los detalles van en `docs/`.

11. **Actualizar `docs/02-arquitectura.md`** — agregar el validator al diagrama
    y describirlo.

## Decisiones de diseño ya tomadas

- **PREMIUM siempre pasa el validator** (no chequeo `gasLimit > blockGasLimit`).
  Razón: `blockGasLimit` puede cambiar, no es una propiedad fija de la cuenta.
  El selector decide con el sobrante real del bloque.
- **El caso "used+txGasLimit > quota"** se queda como `invalidTransient` del
  selector. No se puede chequear en el validator porque no tiene
  `tracker.getUsed(sender)` — eso es estado por bloque.
- **No agrego feature flag** para activar/desactivar el validator. Es puramente
  aditivo (rechaza lo mismo que el selector pero antes). Sin riesgo de cambiar
  comportamiento de TXs válidas.
- **El validator y el selector comparten la misma `TierCache`** — instancia
  única en el plugin. Evita doble lookup al contrato.

## Riesgos / cosas a verificar durante la implementación

- **Latencia de admisión**: cada `eth_sendRawTransaction` ahora hace un lookup
  de tier (cache hit ~µs, miss ~5-50ms vía `eth_call` simulado). Para cache hits
  no impacta; para misses, agrega latencia al submit. La cache absorbe casi
  todo después del primer hit por block.
- **API exacta de Besu**: el interface puede haber cambiado entre versiones. El
  paso 1 lo confirma antes de codear.
- **Block context disponible**: necesito saber el chain head al momento de
  validar para keyear la cache. Si el interface no lo expone directo, uso
  `BlockchainService.getChainHead()`.

## Lo que NO se toca

- El selector actual (`GasMembershipTransactionSelector`) — sigue funcionando
  idéntico para los casos que el validator no cubre.
- El smoke test (`node/smoke-test.sh`) — el comportamiento end-to-end para
  BASIC/STANDARD/PREMIUM no cambia, solo cambia *cuándo* se rechaza (ahora antes).
- El config (`GasMembershipConfig`) — no hay properties nuevas.
