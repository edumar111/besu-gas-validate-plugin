# 04 — Smoke Test End-to-End

> Validación completa del plugin + contrato + nodo Besu como sistema integrado. Detalla los 8
> casos cubiertos, los stages del flujo, y cómo verificar resultados en logs.
>
> Para el "qué" y el "cómo" del plugin, ver
> [`01-requisitos-y-logica.md`](./01-requisitos-y-logica.md) y
> [`02-arquitectura.md`](./02-arquitectura.md).
>
> **Complemento**: para tests más granulares desde el lado del cliente JSON-RPC con Hardhat +
> ethers v6 (que validan específicamente el rechazo upfront de Fase 1.5), ver la carpeta
> [`pruebas/`](../pruebas/) y [`05-fase-1.5-validator-y-pruebas.md`](./05-fase-1.5-validator-y-pruebas.md).
> Este smoke usa `cast`/`forge` directo, los de `pruebas/` usan ethers.

---

## 1. Objetivo

Cerrar el loop **Java ↔ Solidity ↔ Besu** en una red real:

1. Compilar y empaquetar el plugin (`./gradlew build`).
2. Compilar y deployar el contrato `MembershipRegistry`.
3. Cargar el plugin en Besu y verificar el banner de registro.
4. Asignar tiers a cuentas de prueba.
5. Enviar TXs reales desde cada tier y observar el enforcement.

Si los unit tests del plugin (69) y los del contrato (22) verifican la lógica componente por
componente, el smoke test verifica que **el cableado físico funciona**: ServiceLoader, plugin
API, `TransactionSimulationService`, ABI encoding, decoding del retorno, etc.

## 2. Resultados validados (2026-05-21)

**8 / 8 casos comportándose como esperado.** Resumen:

| # | Caso | Cuenta | Tier | Gas pedido | Esperado | Resultado |
|---|---|---|---|---|---|---|
| 1 | Sin membresía | Mallory | NONE | 21K | Rechazada (no membership) | ✓ REJ |
| 2 | Bypass total | Dave | WHITELISTED | 50M | Aceptada (sin límite) | ✓ OK bloque 282 |
| 3 | Dentro de cuota | Alice | BASIC | 21K | Aceptada (≤ 500K) | ✓ OK bloque 283 |
| 4 | Dentro de cuota | Bob | STANDARD | 4M | Aceptada (≤ 5M) | ✓ OK bloque 284 |
| 5 | Dentro de cuota | Carol | PREMIUM | 8M | Aceptada (≤ 10M) | ✓ OK bloque 285 |
| 6 | Excede cuota | Alice | BASIC | 600K | Rechazada (> 500K) | ✓ REJ |
| 7 | PREMIUM con sobrante | Carol | PREMIUM | 50M | Aceptada (usa sobrante) | ✓ OK bloque 688 |
| 8 | Excede cuota | Alice | BASIC | 1M | Rechazada (> 500K) | ✓ REJ |

El script reproducible vive en [`node/smoke-test.sh`](../node/smoke-test.sh).

---

## 3. Stack y requisitos previos

| Item | Versión | Dónde |
|---|---|---|
| Hyperledger Besu | 25.8.0 Falcon (build interno) | `/Users/edumar111/tools/besu-25.8.0-falcon/` |
| JDK | 21 (Homebrew openjdk@21) | `/usr/local/opt/openjdk@21/` |
| Foundry (forge/cast) | 1.4.4 | `/Users/edumar111/.foundry/bin/` |
| Gradle | wrapper 8.10.2 | `plugin/gradlew` (auto-download) |
| OpenZeppelin Contracts | 5.1.0 | `contracts/lib/openzeppelin-contracts/` (forge install) |
| forge-std | 1.16.1 | `contracts/lib/forge-std/` (forge install) |

**Comprobación rápida de prerequisitos**:

```bash
/Users/edumar111/tools/besu-25.8.0-falcon/bin/besu --version 2>&1 | grep version
/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -version 2>&1 | head -1
forge --version
ls /Users/edumar111/lacnet/workspace/CBDC/plugins-gas/contracts/lib/
```

Si te faltan deps del contrato:

```bash
cd contracts && forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 \
                              foundry-rs/forge-std --no-commit
```

---

## 4. Diseño del smoke test — por qué dos stages

El plugin enforza `NONE → reject`. Pero el `MembershipRegistry` no existe hasta que lo deployamos,
y para deployarlo necesitamos enviar una TX desde el deployer — pero ese deployer **no tiene
tier** todavía (porque el contrato no existe).

**Chicken-and-egg**: si arrancáramos el nodo con el plugin activo desde el comienzo, no podríamos
ni siquiera deployar el contrato.

**Solución — dos stages**:

```
                  STAGE A                       STAGE B
                  (sin plugin)                  (con plugin)
                  ───────────                   ───────────
                  Arrancar Besu                 Detener Besu
                       │                              │
                       ▼                              ▼
                  Deploy contrato                Copiar JAR a node/data/plugins/
                       │                              │
                       ▼                              ▼
                  Asignar tiers                  Re-arrancar Besu
                       │                          + BESU_OPTS="-Dratelimit.membershipContract=..."
                       ▼                              │
                  (estado on-chain                    ▼
                   con cuentas+tiers)            Plugin lee el estado on-chain
                                                      │
                                                      ▼
                                                 Smoke test: 8 casos
```

Durante Stage A, el plugin no está cargado (Besu lo ignora porque el JAR no está en
`besu.plugins.dir`). Cualquier TX pasa sin enforcement.

Durante Stage B, el plugin está activo. Las cuentas que asignamos en Stage A operan con sus tiers
correctos.

---

## 5. Cuentas de prueba (deterministas)

Seis claves privadas hardcoded en el script. **No usar en mainnet** — son las claves estándar
que Anvil/Hardhat exponen por default y están públicas.

| Rol | Private key (truncada) | Address | Tier asignado |
|---|---|---|---|
| Deployer / Owner | `0x59c6...690d` | `0x70997970C51812dc3A010C7d01b50e0d17dc79C8` | — (no necesita tier; el plugin ya no lo evalúa después de Stage A) |
| Alice | `0x5de4...365a` | `0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC` | `BASIC` (1) |
| Bob | `0x7c85...07a6` | `0x90F79bf6EB2c4f870365E785982E1f101E93b906` | `STANDARD` (2) |
| Carol | `0x47e1...926a` | `0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65` | `PREMIUM` (3) |
| Dave | `0x8b3a...ffba` | `0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc` | `WHITELISTED` (4) |
| Mallory | `0x92db...564e` | `0x976EA74026E726554dB657fA54763abd0C3a0aa9` | **sin tier** (NONE) |

**Por qué los balances no importan**: la red usa `min-gas-price = 0` y `baseFeePerGas = 0`. Una
TX con `gasPrice = 0` tiene costo total 0, así que ninguna cuenta necesita estar pre-fundeada en
el genesis para enviar TXs. Esto simplifica el setup pero **no es un patrón aplicable a redes
con precio de gas no-cero**.

---

## 6. Stage A — Setup (sin plugin)

### 6.1 Arrancar Besu

```bash
# Sin BESU_OPTS, sin plugin en data/plugins/
mkdir -p node/data/plugins
mv node/data/plugins/*.jar /tmp/ 2>/dev/null || true   # asegurar que está vacío

cd node && ./start-besu.sh > /tmp/besu-stage-a.log 2>&1 &
```

Verifica que arranca:

```bash
curl -X POST http://localhost:4545 -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}'
# {"jsonrpc":"2.0","id":1,"result":"0x9ed2c"}   ← chainId 650540
```

El banner de plugins en stage A muestra:

```
# Plugin Registration Summary:
# No plugins have been registered.
# TOTAL = 0 of 0 plugins successfully registered.
```

Está bien — todavía no copiamos el JAR.

### 6.2 Deploy del `MembershipRegistry`

```bash
DEPLOYER_PK=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d

cd contracts && forge script script/Deploy.s.sol \
    --rpc-url http://localhost:4545 \
    --private-key $DEPLOYER_PK \
    --broadcast --legacy --skip-simulation
```

Salida esperada:

```
== Logs ==
  MembershipRegistry deployed at: 0x8464135c8F25Da09e49BC8782676a84730C318bC
  Owner: 0x70997970C51812dc3A010C7d01b50e0d17dc79C8
```

**Guardá esta dirección** — se usa como `ratelimit.membershipContract` en Stage B.

### 6.3 Asignar tiers (batch)

```bash
CONTRACT=0x8464135c8F25Da09e49BC8782676a84730C318bC
ALICE=0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC
BOB=0x90F79bf6EB2c4f870365E785982E1f101E93b906
CAROL=0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65
DAVE=0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc

cast send $CONTRACT \
    "setTierBatch(address[],uint8[])" \
    "[$ALICE,$BOB,$CAROL,$DAVE]" \
    "[1,2,3,4]" \
    --rpc-url http://localhost:4545 \
    --private-key $DEPLOYER_PK \
    --legacy
```

Verificación:

```bash
for who in $ALICE $BOB $CAROL $DAVE $MALLORY; do
    tier=$(cast call $CONTRACT "getTier(address)(uint8)" $who --rpc-url http://localhost:4545)
    echo "$who → $tier"
done
# 0x3C44...3BC → 1   ← ALICE = BASIC
# 0x90F7...906 → 2   ← BOB = STANDARD
# 0x15d3...A65 → 3   ← CAROL = PREMIUM
# 0x9965...4dc → 4   ← DAVE = WHITELISTED
# 0x976E...0aa9 → 0  ← MALLORY = NONE (nunca se asignó)
```

### 6.4 Detener Besu

```bash
kill $(cat /tmp/besu-stage-a.pid)
```

El estado on-chain (el contrato deployado + los tiers asignados) **persiste** en `node/data/`
para Stage B.

---

## 7. Stage B — Enforcement (con plugin activo)

### 7.1 Copiar el JAR

```bash
cp plugin/build/libs/besu-gas-membership-plugin-0.1.0-SNAPSHOT.jar node/data/plugins/
```

Si no existe el JAR, compilarlo primero:

```bash
cd plugin && ./gradlew build
# Genera build/libs/besu-gas-membership-plugin-0.1.0-SNAPSHOT.jar (~87 KB)
```

### 7.2 Re-arrancar Besu con el plugin

```bash
export BESU_OPTS="-Dratelimit.membershipContract=$CONTRACT"
cd node && ./start-besu.sh > /tmp/besu-stage-b.log 2>&1 &
```

El `start-besu.sh` ya setea `-Dbesu.plugins.dir=node/data/plugins` internamente, así que el plugin
se descubre automáticamente.

### 7.3 Verificar banner de plugins

```bash
grep -B1 -A8 "Plugin Registration Summary" /tmp/besu-stage-b.log
```

Salida esperada:

```
####################################################################################################
#                                                                                                  #
# Plugin Registration Summary:                                                                     #
# Registered Plugins:                                                                              #
#  - GasMembershipPlugin (GasMembershipPlugin/<Unknown Version>)                                   #
# TOTAL = 1 of 1 plugins successfully registered.                                                  #
#                                                                                                  #
####################################################################################################
```

Y los logs del plugin:

```
INFO GasMembershipPlugin: register()
INFO GasMembershipPlugin: registrado (contract=0x8464135c8F25Da09e49BC8782676a84730C318bC \
                                       ttlBlocks=50 \
                                       quotas: BASIC=500000 STANDARD=5000000 PREMIUM=10000000)
INFO Registered plugin of type com.lacnet.besu.gas.GasMembershipPlugin.
INFO GasMembershipPlugin: start()
```

Si NO ves `1 of 1 plugins successfully registered`, ver § 9 (troubleshooting).

---

## 8. Los 8 casos del smoke test

Cada caso envía una TX trivial (self-transfer de 0 wei) variando el `--gas-limit` y la
`--private-key`. Lo importante NO es la TX en sí, sino el `--gas-limit` que el selector usa para
decidir.

### Helper común

```bash
send() {
    local label="$1" pk="$2" gas="$3"
    local from=$(cast wallet address $pk)
    if out=$(cast send "$from" --value 0 --gas-limit "$gas" \
                --rpc-url http://localhost:4545 --private-key "$pk" --legacy --timeout 8 2>&1); then
        local block=$(echo "$out" | grep -E "^blockNumber" | awk '{print $NF}')
        printf "  [OK ] %-30s gas=%-10s block=%s\n" "$label" "$gas" "$block"
    else
        printf "  [REJ] %-30s gas=%-10s (rechazada por el selector)\n" "$label" "$gas"
    fi
}
```

Cuando una TX es rechazada por el selector, `cast send` espera 8 segundos por el receipt y
finalmente da timeout — eso es lo que detectamos como REJ. La TX nunca entra a un bloque porque
el `evaluateTransactionPreProcessing` devolvió INVALID o INVALID_TRANSIENT.

### Caso 1 — MALLORY (NONE) → REJ

```bash
send "MALLORY (NONE)" $MALLORY_PK 21000
```

**Esperado**: `evaluateTransactionPreProcessing` consulta el tier vía el cliente, obtiene
`NONE`, devuelve `invalid("sender has no active membership")`. La TX nunca entra al pool — se
descarta.

**Resultado**: `[REJ] MALLORY (NONE) gas=21000` ✓

### Caso 2 — DAVE (WHITELISTED) con 50M → OK

```bash
send "DAVE (WHITELISTED)" $DAVE_PK 50000000
```

**Esperado**: `tier == WHITELISTED` dispara la rama de bypass — `SELECTED` sin chequear cuota.
La TX entra normalmente al bloque incluso pidiendo 50M de gas (5× la cuota máxima de PREMIUM).

**Resultado**: `[OK ] DAVE (WHITELISTED) gas=50000000 block=282` ✓

### Caso 3 — ALICE (BASIC) con 21K → OK

```bash
send "ALICE (BASIC, dentro)" $ALICE_PK 21000
```

**Esperado**: `tier == BASIC`, cuota 500K. `used=0 + tx.gasLimit=21K = 21K ≤ 500K` → `SELECTED`.

**Resultado**: `[OK ] ALICE (BASIC, dentro) gas=21000 block=283` ✓

### Caso 4 — BOB (STANDARD) con 4M → OK

```bash
send "BOB (STANDARD, dentro)" $BOB_PK 4000000
```

**Esperado**: cuota 5M, `used=0 + 4M = 4M ≤ 5M` → `SELECTED`.

**Resultado**: `[OK ] BOB (STANDARD, dentro) gas=4000000 block=284` ✓

### Caso 5 — CAROL (PREMIUM) con 8M → OK

```bash
send "CAROL (PREMIUM, dentro)" $CAROL_PK 8000000
```

**Esperado**: cuota 10M, `used=0 + 8M = 8M ≤ 10M` → `SELECTED` (sin necesitar sobrante).

**Resultado**: `[OK ] CAROL (PREMIUM, dentro) gas=8000000 block=285` ✓

### Caso 6 — ALICE (BASIC) con 600K → REJ

```bash
send "ALICE (BASIC, > 500K)" $ALICE_PK 600000
```

**Esperado**: cuota 500K. `0 + 600K = 600K > 500K` → no entra en cuota. Como tier != PREMIUM, **no
puede usar sobrante**. Devuelve `invalidTransient("excedió límite de gas en el bloque")`.

**Resultado**: `[REJ] ALICE (BASIC, > 500K) gas=600000` ✓

**Detalle importante**: este es `invalidTransient`, no `invalid` — la TX queda en el txpool y se
reintentará en el bloque siguiente (cuando los contadores se reseteen). El timeout del `cast send`
no significa que la TX se descartó del pool; solo que no entró en ningún bloque dentro de los 8s
de espera del cliente. Si Alice no envía otra TX que llene 500K en el siguiente bloque, esta
podría entrar.

### Caso 7 — CAROL (PREMIUM) con 50M → OK (sobrante)

```bash
send "CAROL PREMIUM con sobrante" $CAROL_PK 50000000
```

**Esperado**: cuota 10M; `tx.gasLimit = 50M > 10M`. Entra en la rama PREMIUM-sobrante:
`leftover = blockGasLimit (350M) - totalUsed`. Como Carol es la única que usa gas en el bloque
nuevo (el cambio de bloque resetea el tracker), `totalUsed = 0` → `leftover = 350M ≥ 50M` →
`SELECTED`.

**Resultado**: `[OK ] CAROL PREMIUM con sobrante gas=50000000 block=688` ✓

Este es **el caso más interesante del flujo** — el sobrante es lo que diferencia a PREMIUM de
STANDARD y es lo que demuestra que el plugin entiende el block gas limit y la contabilidad
multi-sender.

### Caso 8 — ALICE (BASIC) con 1M → REJ

```bash
send "ALICE (BASIC, 1M > 500K)" $ALICE_PK 1000000
```

**Esperado**: igual al Caso 6 pero con valores mayores. Cuota 500K, pide 1M → `invalidTransient`.

**Resultado**: `[REJ] ALICE (BASIC, 1M > 500K) gas=1000000` ✓

---

## 9. Script reproducible — `node/smoke-test.sh`

El script automatiza Stage A + Stage B + los 8 casos. Para correrlo:

```bash
# Asegurate de tener el JAR compilado y las deps de Foundry
cd plugin && ./gradlew build
cd ../contracts && forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 \
                                 foundry-rs/forge-std --no-commit
cd ..

# Correr
./node/smoke-test.sh
```

Tiempo total: **~3 minutos** (incluyendo arranques de Besu, deploy, asignación, 8 TXs).

### Qué hace internamente

1. **`cleanup_node`** (trap EXIT): mata cualquier Besu colgado del mismo data-path.
2. **Stage A**:
   - Vacía `node/data/plugins/` (mueve cualquier JAR a `/tmp/`).
   - `unset BESU_OPTS` para no contaminar.
   - Arranca Besu (`start-besu.sh`).
   - Espera al RPC.
   - Deploy del contrato con `forge script`.
   - Asigna tiers con `cast send setTierBatch`.
   - Verifica con `cast call getTier` para cada cuenta.
   - Detiene Besu.
3. **Stage B**:
   - Copia el JAR del plugin a `node/data/plugins/`.
   - Exporta `BESU_OPTS=-Dratelimit.membershipContract=$CONTRACT`.
   - Arranca Besu.
   - Verifica que el banner de plugins muestre `1 of 1 plugins successfully registered`.
   - Corre los 8 casos via la función `send`.
4. **Cleanup**: deja Besu corriendo al final (con un mensaje que indica cómo matarlo).

### Logs generados

- `/tmp/besu-smoke-stage-a.log` — output del Stage A (sin plugin).
- `/tmp/besu-smoke-stage-b.log` — output del Stage B (con plugin). Acá viven los mensajes del
  plugin y los rechazos del selector (en DEBUG).

---

## 10. Verificación de resultados {#troubleshooting}

### El plugin NO se carga

Síntoma: el banner muestra `0 of 0 plugins successfully registered`.

| Causa | Verificación | Solución |
|---|---|---|
| JAR no está en `besu.plugins.dir` | `ls node/data/plugins/` | Copiar el JAR ahí |
| `besu.plugins.dir` apunta a otra ruta | `ps -p $(cat /tmp/besu-stage-b.pid) -o args \| grep besu.plugins.dir` | Asegurarse que `start-besu.sh` setea la prop correctamente |
| JAR corrupto / falta el SPI | `unzip -p plugin/build/libs/*.jar META-INF/services/org.hyperledger.besu.plugin.BesuPlugin` | Debe imprimir `com.lacnet.besu.gas.GasMembershipPlugin` |

### El plugin se carga pero rechaza todo

Síntoma: todas las TXs (incluso BASIC con 21K) son rechazadas.

Causa probable: `ratelimit.membershipContract` apunta a una address vacía, o el contrato no
responde.

```bash
# Verificá que el contrato exista en la red
cast code $CONTRACT --rpc-url http://localhost:4545
# Si devuelve "0x", el contrato no está deployado en esta dirección.

# Verificá que getTier responda
cast call $CONTRACT "getTier(address)(uint8)" $ALICE --rpc-url http://localhost:4545
# Debería devolver 0..4
```

Si el cliente devuelve cualquier error o tarda mucho, el plugin trata el tier como `NONE` (modo
seguro), por eso todo se rechaza.

### Los rechazos no aparecen en los logs INFO

Los rechazos del selector se loguean en DEBUG (`LOG.debug("Rechazo sender=...")`), no en INFO.
Para verlos:

```bash
# Editar node/log.xml temporalmente para subir el nivel del logger del plugin
# O arrancar Besu con --logging=DEBUG (advertencia: log muy verbose)
```

En INFO solo vas a ver el cableado inicial:

```
GasMembershipPlugin: register()
GasMembershipPlugin: registrado (...)
GasMembershipPlugin: start()
```

### `cast send` da timeout pero la TX entra al bloque

Si la TX entra al bloque DESPUÉS del timeout de 8s del cliente, vas a ver REJ aunque la TX se
incluyó. Esto puede pasar si el bloque tarda más de lo normal en producirse (raro con block
period 2s).

```bash
# Verificá si la TX está en algún bloque reciente con su hash
cast tx <tx-hash> --rpc-url http://localhost:4545
```

### El contrato se deployó pero falla la verificación de tiers

```bash
# Si `cast call getTier` devuelve 0 después de un setTier supuestamente exitoso,
# revisá el receipt del setTier:
cast receipt <tx-hash-del-setTier> --rpc-url http://localhost:4545
# Confirmá status=1 (success) y que el event TierAssigned se emitió.
```

---

## 11. Otros plugins de Besu en el sistema

Antes del smoke test final, eliminamos JARs de plugins viejos que vivían en otras instalaciones
de Besu (no la falcon):

- `/Users/edumar111/tools/besu-25.8.0/plugins/besu-plugins-permissioned-1.0.0.jar` — removido
- `/Users/edumar111/tools/besu-23.4.1/plugins/besu-plugins-permissioned-1.0.0.jar` — removido

Backup en `/tmp/besu-plugins-backup/` por si hubiera que restaurar.

**Nuestro Besu (falcon)** vive en `/Users/edumar111/tools/besu-25.8.0-falcon/` y NO tiene un
directorio `plugins/` por default. Combinado con `-Dbesu.plugins.dir=node/data/plugins`,
queda totalmente aislado — solo carga lo que copiamos a esa ruta.

---

## 12. Lo que el smoke test NO valida

- **Tiempo de respuesta bajo carga real** (cientos de TX/s). Los unit tests cubren la lógica;
  un test de stress es separado.
- **Comportamiento bajo block reorg** (no esperado en QBFT, pero podría ocurrir si el
  validador único cae). Tampoco esperamos forks.
- **Cuota mensual (Fase 2)** — este smoke test cubre solo el enforcement per-block de Fase 1. La
  validación E2E de la cuota mensual + bloqueo 5 min + commit on-chain del recorder está en un script
  aparte, [`node/e2e-fase2.sh`](../node/e2e-fase2.sh) (ver
  [`07-fase-2-cuota-mensual.md`](./07-fase-2-cuota-mensual.md)).
- **Restart del plugin sin reiniciar Besu** — Besu no soporta hot-reload de plugins. Cualquier
  cambio en config o código del plugin requiere reinicio del nodo.

---

## 13. Lecturas relacionadas

- [Requisitos y lógica](./01-requisitos-y-logica.md) — modelo de membresía y reglas.
- [Arquitectura](./02-arquitectura.md) — componentes Java internos.
- [Contratos y tests](./03-contratos.md) — el contrato `MembershipRegistry` y sus tests.
- [`node/smoke-test.sh`](../node/smoke-test.sh) — el script ejecutable.
