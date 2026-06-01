# 05 — Fase 1.5: Validator y carpeta `pruebas/`

> Esta página documenta los cambios entre **Fase 1 cerrada** (commit `442195b`) y el estado
> actual del repositorio. Si venís a leer la arquitectura general primero, empezá por
> [`02-arquitectura.md`](./02-arquitectura.md).

---

## Resumen ejecutivo

Fase 1.5 agrega un **validator** que rechaza al admitir al txpool las transacciones que el
plugin sabe que nunca van a poder ser incluidas. Sin esto, esas TXs se admitían al pool y solo
el selector las descartaba después (al armar el bloque) — el cliente JSON-RPC no se enteraba y
tenía que pollear hasta darse cuenta. Con el validator, `eth_sendRawTransaction` devuelve error
directo y `ethers` tira excepción en milisegundos.

Cambios en una línea cada uno:

- **Validator nuevo** que corre en admisión al pool (Fase 1.5).
- **Cambio de comportamiento en el selector**: `txGasLimit > tierQuota` ahora es `invalid`
  permanente en vez de `invalidTransient`.
- **Carpeta `pruebas/`** nueva: scripts Hardhat + ethers v6 para probar caso positivo y negativo.
- **`start-besu.sh` modificado**: ahora exporta `-Dratelimit.membershipContract` con la addr
  hardcodeada, evitando el dance con `BESU_OPTS`.

---

## 1. Plugins implementados (clases Java)

El JAR del plugin registra contra Besu **dos componentes plugin** distintos. Cada uno
implementa una interface diferente del plugin-api y se registra contra un servicio diferente
del runtime.

### 1.1 Entry point del plugin

| Clase Java | Interface Besu | Servicio Besu | Cuándo lo invoca Besu |
|---|---|---|---|
| `com.lacnet.besu.gas.GasMembershipPlugin` | `org.hyperledger.besu.plugin.BesuPlugin` | (cargado por SPI — `META-INF/services/...`) | Al arranque del nodo |

Responsabilidad: cablear los componentes, leer system properties, obtener servicios del runtime,
registrar los dos factories. Falla early si la config está mal.

### 1.2 Plugins registrados contra servicios

| # | Clase Java | Implementa | Se registra contra | Cuándo decide | Fase |
|---|---|---|---|---|---|
| **1** | `com.lacnet.besu.gas.selector.GasMembershipTransactionSelector` | `PluginTransactionSelector` | `TransactionSelectionService` | Al armar cada bloque | **Fase 1** |
| **2** | `com.lacnet.besu.gas.validator.GasMembershipTransactionValidator` | `PluginTransactionPoolValidator` | `TransactionPoolValidatorService` | Al admitir cada TX al txpool (`eth_sendRawTransaction`) | **Fase 1.5** |

Sus respectivos factories:

| Factory Java | Crea instancias de |
|---|---|
| `com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorFactory` | `GasMembershipTransactionSelector` |
| `com.lacnet.besu.gas.validator.GasMembershipTransactionValidatorFactory` | `GasMembershipTransactionValidator` |

**Estado compartido entre ambos plugins**:

```
GasMembershipPlugin.register()
    ├── crea MembershipContractClient (singleton)
    ├── crea TierCache (singleton, TTL=50 bloques)
    ├── crea BlockGasTracker (singleton)
    │
    ├── crea SelectorFactory(config, client, cache, tracker)
    │       └── selection.registerPluginTransactionSelectorFactory(...)
    │
    └── crea ValidatorFactory(config, client, cache, simulator)
            └── validation.registerPluginTransactionValidatorFactory(...)
```

`MembershipContractClient` y `TierCache` se comparten entre ambos plugins — **un solo lookup
al contrato por `(sender, bloque)` sirve a ambos puntos de decisión**.

---

## 2. División de responsabilidades: validator vs selector

| Caso | Validator (al submit) | Selector (al armar bloque) | Resultado final |
|---|---|---|---|
| `tier == WHITELISTED` | acepta | `SELECTED` | TX entra siempre |
| `tier == NONE` | **rechaza** `REASON_NO_MEMBERSHIP` | (no llega) | `eth_sendRawTransaction` falla |
| `tier == BASIC/STANDARD` + `txGasLimit > tierQuota` | **rechaza** `REASON_TX_EXCEEDS_TIER_QUOTA` | (no llega) | `eth_sendRawTransaction` falla |
| `tier == BASIC/STANDARD` + `txGasLimit ≤ tierQuota` + `used + txGasLimit ≤ quota` | acepta | `SELECTED` | TX entra al bloque actual |
| `tier == BASIC/STANDARD` + `txGasLimit ≤ tierQuota` + `used + txGasLimit > quota` | acepta | `invalidTransient` (`REASON_BLOCK_QUOTA_EXCEEDED`) | TX queda en pool, reintenta próximo bloque |
| `tier == PREMIUM` + `txGasLimit ≤ tierQuota` | acepta | `SELECTED` | TX entra |
| `tier == PREMIUM` + `txGasLimit > tierQuota` + cabe en sobrante del bloque | acepta | `SELECTED` (usa sobrante) | TX entra |
| `tier == PREMIUM` + no cabe ni en cuota ni en sobrante | acepta | `invalidTransient` | TX queda en pool |

**Reglas clave**:

- El validator solo rechaza lo que es **estructuralmente imposible** (no depende del estado del bloque actual). Por eso PREMIUM siempre pasa el validator: el block leftover puede cambiar, no es propiedad fija.
- El selector funciona idéntico a Fase 1 — el validator es **aditivo**, no lo reemplaza. Para casos no cubiertos por el validator (PREMIUM, accounting per-block) el selector sigue siendo la única defensa.
- Para los casos que el validator **sí** rechaza, el selector queda como red de seguridad redundante: si el validator no estuviera (versión vieja del JAR, error de registro), el selector lo atrapa igual. Es por defensa en profundidad.

---

## 3. Cambio de comportamiento en el selector (Fase 1 → Fase 1.5)

### Antes

```java
// Cualquier exceso de cuota → invalidTransient (TX queda en pool, reintenta)
return TransactionSelectionResult.invalidTransient(REASON_BLOCK_QUOTA_EXCEEDED);
```

### Ahora

```java
// Caso permanente: txGasLimit > tierQuota → invalid (descarte del pool)
if (txGasLimit > quota) {
    return TransactionSelectionResult.invalid(REASON_TX_EXCEEDS_TIER_QUOTA);
}
// Caso transitorio: used + txGasLimit > quota pero txGasLimit ≤ quota
return TransactionSelectionResult.invalidTransient(REASON_BLOCK_QUOTA_EXCEEDED);
```

**Constante nueva**: `GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA = "tx gasLimit exceeds tier quota"`.

El validator reutiliza esta misma constante, así el cliente recibe el mismo mensaje
independientemente de qué componente lo rechazó. Esto vive en el selector porque la constante
existía antes y el validator depende del selector (uni-direccional).

**Por qué este cambio**: con Fase 1 el caso `txGasLimit > tierQuota` quedaba en el txpool
para siempre (la TX no iba a caber NUNCA en ningún bloque, pero el selector seguía devolviendo
`invalidTransient`). Con Fase 1.5, ese mismo caso lo detecta el validator antes; el cambio en
el selector mantiene coherencia para el caso degenerado donde el validator no esté.

---

## 4. Carpeta `pruebas/`

Carpeta nueva con un proyecto Hardhat + ethers v6 + TypeScript para probar end-to-end el plugin
desde el lado del cliente JSON-RPC. Complementa al `node/smoke-test.sh` existente (que usa
`cast` de Foundry directamente).

### 4.1 Estructura

```
pruebas/
├── package.json
├── hardhat.config.ts
├── tsconfig.json
├── .env.example
├── .gitignore
├── README.md
├── contracts/
│   ├── Storage.sol         ← contrato chico (deploy ~110K gas)
│   └── FatStorage.sol      ← contrato grande (deploy ~700K gas)
└── scripts/
    ├── deploy-as-basic.ts             ← caso positivo
    └── deploy-exceeding-basic-limit.ts ← caso negativo
```

### 4.2 Scripts

| Comando | Qué hace |
|---|---|
| `npm run deploy:basic` | Asigna BASIC a la wallet (si no estaba), deploya `Storage` (~110K gas), reporta gas usado vs límite. **Caso positivo**: BASIC permite deploys chicos. |
| `npm run deploy:exceed` | Verifica que la wallet es BASIC, intenta deployar `FatStorage` con `gasLimit: 800_000`. **Caso negativo**: el validator rechaza al submit, `factory.deploy()` tira excepción en ~50ms. |

### 4.3 Bootstrap del registry (una vez por entorno)

El plugin requiere la addr del `MembershipRegistry` como system property al arranque. Esa
addr no existe la primera vez. Hay que bootstrappearlo:

1. Sacar el JAR del plugin de `node/data/plugins/`.
2. Arrancar Besu sin plugin.
3. Deployar el registry con `forge script script/Deploy.s.sol` y guardar la addr en `pruebas/.registry-address`.
4. **Whitelistear al owner** del registry: `cast send <REGISTRY> setTier(address,uint8) <OWNER_ADDR> 4` — sin esto, cuando arranque Besu con el plugin, el owner queda con tier NONE y no va a poder llamar `setTier` para asignar tiers a otras cuentas.
5. Parar Besu, restaurar el JAR.
6. Editar `node/start-besu.sh` con la addr del registry (variable `MEMBERSHIP_CONTRACT`).
7. Arrancar Besu con el plugin.

Detalle de los comandos exactos en [`pruebas/README.md`](../pruebas/README.md#bootstrap-del-registry-primera-vez).

### 4.4 Por qué el `deploy-exceeding-basic-limit.ts` no espera con timeout

En la versión inicial (Fase 1) el script polleaba con timeout de 30s (la TX quedaba colgada en
el pool indefinidamente). Después del cambio en el selector se redujo a polling cada 1s hasta
detectar que la TX desapareció del pool. Con el validator (Fase 1.5), ya no hace falta polling:
el RPC devuelve error en el `eth_sendRawTransaction` y ethers tira excepción inmediata. El
script es ahora un `try { factory.deploy() } catch (e) { reportar }` sin timers ni loops.

---

## 5. Modificación a `start-besu.sh`

Antes (Fase 1):

```bash
export JAVA_OPTS="${JAVA_OPTS:-} -Dbesu.logs.dir=${LOGS_DIR} -Dbesu.plugins.dir=${PLUGINS_DIR}"
```

Ahora:

```bash
MEMBERSHIP_CONTRACT="${MEMBERSHIP_CONTRACT:-0x948B3c65b89DF0B4894ABE91E6D02FE579834F8F}"
export JAVA_OPTS="${JAVA_OPTS:-} -Dbesu.logs.dir=${LOGS_DIR} -Dbesu.plugins.dir=${PLUGINS_DIR} -Dratelimit.membershipContract=${MEMBERSHIP_CONTRACT}"
```

La addr del registry quedó hardcodeada como default pero overrideable por env var. Antes había
que pasarla cada vez con `BESU_OPTS="-Dratelimit.membershipContract=..." ./node/start-besu.sh`.

Cuando se redeploye el registry, editar la línea o exportar la env var:

```bash
MEMBERSHIP_CONTRACT=0x... ./node/start-besu.sh
```

---

## 6. Tests agregados

Pasamos de **60 tests Java a 69 tests Java** (todos verdes). Desglose:

| Test file | Tests nuevos | Qué cubre |
|---|---|---|
| `GasMembershipTransactionValidatorTest.java` | 7 | WHITELISTED / PREMIUM / NONE / BASIC>quota / BASIC≤quota / STANDARD>quota / cache hit |
| `GasMembershipTransactionSelectorTest.java` | +2 (uno renombrado) | `basicTxGasLimitMayorQueQuotaEsInvalidPermanente`, `basicTxGasLimitMenorQueQuotaPeroBloqueAgotadoEsInvalidTransient` |
| `GasMembershipPluginTest.java` | +1 | `register_fallaSiTransactionPoolValidatorServiceNoEstaDisponible` |

**Modificados**:

- `GasMembershipPluginTest.register_cableaTodoYRegistraElFactory`: ahora también verifica que el validator factory se registre.

---

## 7. Cómo verificar end-to-end (paso a paso)

Desde la raíz del repo. Asume que el bootstrap del registry ya se hizo y la addr está en `pruebas/.registry-address` y en `start-besu.sh`.

```bash
# 1. Compilar el plugin (corre 69 tests, genera JAR shadowed)
cd plugin && ./gradlew clean test build && cd ..

# 2. Parar Besu y reemplazar el JAR
pkill -f "besu.*--data-path=${PWD}/node/data" 2>/dev/null; sleep 2
rm -f node/data/plugins/besu-gas-membership-plugin-*.jar
cp plugin/build/libs/besu-gas-membership-plugin-*.jar node/data/plugins/

# 3. Arrancar Besu
./node/start-besu.sh > /tmp/besu.log 2>&1 &
sleep 10

# 4. Verificar que el plugin levantó OK
grep "GasMembershipPlugin" /tmp/besu.log
# Debería mostrar:
#   GasMembershipPlugin: register()
#   GasMembershipPlugin: registrado (contract=0x... ...)

# 5. Probar caso positivo (debería tardar ~3s, gas usado ~110K)
cd pruebas && npm run deploy:basic

# 6. Probar caso negativo (debería tardar ~50ms, rechazo upfront)
npm run deploy:exceed
```

Lo que confirma que estás corriendo el JAR de Fase 1.5 (no uno viejo):

- En `npm run deploy:exceed` el campo **Tiempo** debe ser milisegundos (~50ms), no segundos.
- La **Razon del nodo** debe contener `tx gasLimit exceeds tier quota`.

Si el tiempo es ~3000ms o el campo razón es vacío/genérico, estás en el JAR de Fase 1 todavía.

---

## 8. Limitaciones y siguientes pasos

### Lo que la Fase 1.5 NO resuelve

El validator no rechaza el caso donde **N TXs individuales válidas suman más que la quota
per-block**:

```
ALICE (BASIC, quota 500K) manda 3 TXs de 200K en sucesión rápida.
  Validator: las 3 pasan (cada una 200K ≤ 500K quota).
  Pool acepta las 3.
  Selector arma bloque N: TX1 (200K, used=0) → SELECTED. TX2 (200K, used=200K) → SELECTED. used=400K.
                          TX3 (200K, used=400K, projected=600K > 500K) → invalidTransient.
  TX3 queda en pool, se reintenta en bloque N+1.
```

Para este caso solo el selector puede decidir (necesita `used` per-block que el validator no
tiene). El cliente no se entera en tiempo real — sigue siendo poll-based vía `getTransaction`.

Si en algún momento se necesita notificación realtime para este caso, hay tres caminos posibles
(documentados en la discusión que originó Fase 1.5):

- Custom RPC `gasMembership_getRejection(txHash)` con cache en memoria del plugin → polling más eficiente.
- WebSocket subscription custom (`eth_subscribe("gasRejections")`) → push realtime.
- Mantener el polling actual sobre `getTransaction` (lo más estándar).

Ninguno está implementado.

### Fase 2 (implementada después de 1.5)

Cuota mensual on-chain + bloqueo 5 min. Construida **encima** de Fase 1.5 sin cambiarla: el
`MonthlyQuotaGuard` se consulta en el validator (y en el selector) **antes** de la lógica per-block
de 1.5. Componentes: `UsageMeter.sol`, `UsageBlockListener` (`BlockAddedListener`),
`BlockedAddressRegistry`, nodo recorder. Ver [`07-fase-2-cuota-mensual.md`](./07-fase-2-cuota-mensual.md).
