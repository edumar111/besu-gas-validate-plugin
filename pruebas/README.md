# pruebas/

Script ejecutable que deploya un contrato `Storage` dummy desde una cuenta
registrada como **BASIC** en `MembershipRegistry`. Sirve para validar
end-to-end que el plugin de gas acepta el deploy cuando la cuenta tiene tier
BASIC y el consumo queda bajo el límite (500 000 gas).

## Pre-condiciones

1. **Plugin compilado y copiado a `node/data/plugins/`**:
   ```bash
   # Desde la raíz del repo
   cd plugin && ./gradlew build
   cp build/libs/besu-gas-membership-plugin-*.jar ../node/data/plugins/
   ```

2. **Foundry instalado** (`forge` en el PATH). Lo usás para el bootstrap del
   registry; además, `predeploy:basic` corre `forge build` para refrescar el
   artefacto que el script lee.

3. **Node ≥ 20**.

> **Importante**: el plugin necesita la dirección del `MembershipRegistry` como
> propiedad de sistema al arrancar. La primera vez esa dirección no existe —
> hay que bootstrapearla con Besu corriendo SIN el plugin. Ver siguiente sección.

## Bootstrap del registry (primera vez)

El plugin no deja arrancar Besu sin la dirección del registry. Como aún no
existe, hay que deployarlo primero con Besu sin plugin. Es un bootstrap único:
el state queda persistido en `node/data/` entre reinicios.

Desde la raíz del repo:

```bash
# 1. Sacar el JAR del plugin de plugins/
mv node/data/plugins/besu-gas-membership-plugin-*.jar /tmp/

# 2. Arrancar Besu sin plugin (background)
./node/start-besu.sh > /tmp/besu-bootstrap.log 2>&1 &

# 3. Esperar a que el RPC esté listo
until curl -fsS -X POST http://localhost:4545 \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' >/dev/null 2>&1; do
  sleep 1
done

# 4. Deployar MembershipRegistry con Foundry y guardar la addr
DEPLOY_OUT=$(cd contracts && forge script script/Deploy.s.sol \
  --rpc-url http://localhost:4545 \
  --private-key 0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d \
  --broadcast --legacy --skip-simulation 2>&1)
REGISTRY=$(echo "$DEPLOY_OUT" | grep "MembershipRegistry deployed at:" | awk '{print $NF}')
echo "Registry deployado en: $REGISTRY"
mkdir -p pruebas && echo -n "$REGISTRY" > pruebas/.registry-address

# 5. Whitelistear al owner (tier=4=WHITELISTED).
#    Sin esto, cuando arranques Besu con el plugin, el owner no podrá mandar
#    setTier porque su propio tier es NONE → el plugin lo rechaza con
#    "sender has no active membership". WHITELISTED bypassa toda lógica de cuota.
cast send "$REGISTRY" "setTier(address,uint8)" \
  0x70997970C51812dc3A010C7d01b50e0d17dc79C8 4 \
  --rpc-url http://localhost:4545 \
  --private-key 0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d \
  --legacy

# 6. Parar Besu y restaurar el JAR
pkill -f "besu.*--data-path=${PWD}/node/data"
sleep 2
mv /tmp/besu-gas-membership-plugin-*.jar node/data/plugins/
```

Al terminar, `pruebas/.registry-address` contiene la addr del registry y el owner
queda como WHITELISTED, listo para asignar tiers con el plugin activo.

## Arrancar Besu con el plugin

En cada sesión (incluida la primera, después del bootstrap):

```bash
BESU_OPTS="-Dratelimit.membershipContract=$(cat pruebas/.registry-address)" \
  ./node/start-besu.sh
```

`BESU_OPTS` es la variable que el launcher de Besu lee automáticamente y propaga
a la JVM como flag `-D`. Mientras no borres `node/data/`, el registry persiste
en esa addr — un solo bootstrap alcanza para todas las sesiones siguientes.

## Instalación

```bash
cd pruebas
npm install
```

Solo la primera vez (descarga Hardhat, ethers v6, dotenv y demás devDeps).

## Configuración (opcional)

Por defecto el script usa las cuentas y el RPC del smoke test
(`DEPLOYER_PK` como owner del registry, `ALICE_PK` como deployer BASIC,
RPC en `http://localhost:4545`). Si querés overridear:

```bash
cp .env.example .env
# editar .env con tus valores
```

Variables soportadas:

| Var | Default | Descripción |
|---|---|---|
| `RPC_URL` | `http://localhost:4545` | Endpoint JSON-RPC de Besu. |
| `OWNER_PK` | `0x59c6...690d` | PK del owner del registry (puede llamar `setTier`). |
| `BASIC_PK` | `0x5de4...365a` | PK de la cuenta que se registra como BASIC y deploya el Storage. |
| `REGISTRY_ADDRESS` | (vacío) | Si está seteada, reusa ese registry. Si no, el script lo deploya o lee de `pruebas/.registry-address`. |

## Ejecución

### Caso positivo: deploy DENTRO del límite BASIC

```bash
npm run deploy:basic
```

Esto corre dos pasos en orden:

1. `cd ../contracts && forge build` — refresca el artefacto del registry.
2. `hardhat run scripts/deploy-as-basic.ts --network besuLocal` — ejecuta el script.

### Caso negativo: deploy QUE EXCEDE el límite BASIC

```bash
npm run deploy:exceed
```

Mismo deployer (ALICE, tier BASIC), pero ahora intenta deployar `FatStorage`
cuyo constructor consume ~700K gas — supera el límite BASIC de 500K. El
**validator del plugin (Fase 1.5)** rechaza la TX al admitirla al txpool, así
que `eth_sendRawTransaction` devuelve error y `factory.deploy()` tira excepción
en milisegundos. **Sin polling, sin timeout** — la decisión llega upfront.

**Pre-condición**: la cuenta `BASIC_PK` tiene que estar ya registrada como
BASIC. Corré `npm run deploy:basic` al menos una vez antes.

## Qué hace cada script

### `deploy-as-basic.ts` (caso positivo)

1. Conecta a Besu (`RPC_URL`) y valida `chainId === 650540`.
2. Resuelve la dirección del `MembershipRegistry`:
   - Si `REGISTRY_ADDRESS` está en el env, la usa.
   - Si existe `pruebas/.registry-address` (lo que dejó el bootstrap con Foundry), lee la addr de ahí y verifica que tenga bytecode.
   - **Fallback**: si no encuentra ninguna, intenta deployar uno con `OWNER_PK`. Solo funciona si Besu corre sin el plugin — fuera del bootstrap, esta rama va a fallar porque `OWNER_PK` no tiene tier asignado.
3. Asegura tier BASIC para `BASIC_PK`:
   - Si ya es BASIC → skip (idempotente).
   - Si no → el owner llama `setTier(target, 1)` y verifica que quedó.
4. Compila `contracts/Storage.sol` (Hardhat) y lo deploya firmado por `BASIC_PK`.
5. Loguea TX hash, dirección del Storage, gas usado vs límite BASIC, y status.

### `deploy-exceeding-basic-limit.ts` (caso negativo)

1. Conecta a Besu y valida `chainId`.
2. Resuelve `REGISTRY_ADDRESS` (env o `.registry-address`). No deploya el registry — si no lo encuentra, error.
3. **Verifica** que `BASIC_PK` ya es BASIC. Si no lo es, error indicando que corras `deploy:basic` primero.
4. Compila `contracts/FatStorage.sol` (Hardhat) y lo deploya firmado por `BASIC_PK` con `gasLimit: 800_000`.
5. Como `tx.gasLimit (800K) > BASIC quota (500K)`, el **validator** del plugin lo rechaza en `eth_sendRawTransaction`. ethers atrapa el error y entra en el `catch`.
6. Imprime resultado:
   - **Excepción** → "TX RECHAZADA AL SUBMIT (esperado)" + tiempo (ms) + razón del nodo + comparación gasLimit vs límite BASIC.
   - **Deploy exitoso** (no debería pasar) → "ADMITIDA AL POOL (INESPERADO)" + sugerencias de qué revisar (probablemente el plugin no tiene el JAR con Fase 1.5).

## Salida esperada

### `deploy:basic` (caso positivo)

```
===========================================
Deploy as BASIC tier
===========================================
chainId:          650540
Owner:            0x70997970C51812dc3A010C7d01b50e0d17dc79C8
Basic deployer:   0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC

Registry desde archivo: 0xabc...
Registry:         0xabc...
Tier de 0x3C44...: BASIC (ya estaba)

Deployando Storage como 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC...
TX hash:          0x...

===========================================
Resultado
===========================================
Storage addr:     0xdef...
Gas usado:        110 234 / 500 000 (22.0% del limite BASIC)
Status:           OK
```

### `deploy:exceed` (caso negativo, esperado)

```
===========================================
Caso negativo: deploy > limite BASIC
===========================================
chainId:          650540
Deployer (BASIC): 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC

Registry:         0xabc...
Tier confirmado:  BASIC

Deployando FatStorage como 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC...
Gas limit de la TX: 800 000 (mayor a limite BASIC 500 000)

===========================================
Resultado: TX RECHAZADA AL SUBMIT (esperado)
===========================================
Tiempo:           45 ms
Gas limit TX:     800 000
Limite BASIC:     500 000
Exceso:           300 000 (+60%)

Razon del nodo:   tx gasLimit exceeds tier quota

El validator del plugin rechazo la TX en eth_sendRawTransaction antes
de que entre al txpool. Sin polling, sin timeout — error directo.
```

## Troubleshooting

| Síntoma | Causa probable |
|---|---|
| `No se pudo conectar al RPC` | Besu no está corriendo. Arrancalo con `./node/start-besu.sh`. |
| `chainId esperado 650540, encontrado X` | Estás apuntando a otra red. Revisá `RPC_URL`. |
| `Artefacto del MembershipRegistry no encontrado` | Falta correr `forge build` en `contracts/`. El `predeploy:basic` lo hace, pero si lo invocás directo con `hardhat run` tenés que correrlo manualmente. |
| `El deploy fue rechazado` | El plugin rechazó la TX (cuenta sin tier, sin gas suficiente para el tier asignado, o block budget agotado). Verificá que el plugin esté cargado y que el setTier haya quedado. |
| `setTier no quedo como BASIC` | El `OWNER_PK` no es realmente el owner del registry — revisá qué cuenta lo deployó. |
| `Tier de X: NONE → asignando BASIC...` se queda colgado | El owner del registry no es WHITELISTED, entonces el plugin rechaza su `setTier` como `tier=NONE`. Bloques se producen vacíos (0 tx / 0 pending en logs de Besu). Solución: parar Besu, sacar el JAR, arrancar sin plugin, hacer `cast send <REGISTRY> setTier <OWNER_ADDR> 4` (WHITELISTED), restaurar el JAR, reiniciar. La sección "Bootstrap del registry" hace esto en el paso 5. |

## Limpieza

Para empezar de cero (registry nuevo en la próxima corrida):

```bash
rm .registry-address
```

Para borrar la build local:

```bash
rm -rf cache/ artifacts/ typechain-types/
```
