# 01 — Requisitos y Lógica del Plugin

> Qué hace el plugin, qué decisiones de producto están cerradas, y cómo se comporta ante cada
> situación. Este es el "manual del producto" — la parte ortogonal a la implementación.
>
> Ver también: [Arquitectura](./02-arquitectura.md) (el "cómo"), [Contratos](./03-contratos.md),
> [Smoke test E2E](./04-smoke-test-e2e.md).

---

## 1. Objetivo

Controlar el consumo de gas en una **CBDC privada con gas price = 0** mediante un sistema de
**membresías pagas por tier** que cumple dos objetivos:

| # | Objetivo | Mecanismo | Fase |
|---|---|---|---|
| 1 | Anti-spam y fairness por bloque | Cuota máxima de gas/bloque por tier | Fase 1 (**hecho**) |
| 2 | Monetización del servicio | Cuota mensual contratada por tier | Fase 2 (pendiente) |

Por qué los dos mecanismos no se sustituyen entre sí:

- **Sólo per-block** dejaría que una cuenta consuma toda su quota cada bloque durante 30 días sin
  pagar más — la red sería sostenible pero el modelo de negocio no.
- **Sólo per-month** dejaría que una cuenta PREMIUM con 1B gas/mes envíe **350M (todo el block
  gas limit) en un solo bloque** — tumbaría la inclusión de los demás tiers durante ese bloque.

Por eso el roadmap tiene ambas dimensiones.

---

## 2. Entorno objetivo (valores verificados)

| Parámetro | Valor | Fuente |
|---|---|---|
| Cliente | Hyperledger Besu **25.8.0 Falcon** | binario local |
| Consenso | QBFT | `node/genesis.json` |
| `chainId` / network-id | **650540** | `genesis.json` |
| Block period | ~2 segundos | `qbft.blockperiodseconds: 2` |
| **Block gas limit** | **350,000,000** (`0x14DC9380`) | verificado vía `eth_getBlockByNumber` |
| `baseFeePerGas` | 0 | zero-gas private net |
| RPC HTTP | `http://localhost:4545` | `node/config.toml` |
| RPC WS | `ws://localhost:4546` | `node/config.toml` |
| GraphQL | `http://localhost:4547` | `node/config.toml` |

**Atención al puerto**: la red usa `4545/4546/4547`, NO los `8545/8546` por defecto de Besu. Todos
los clientes (`cast`, scripts, dashboards) deben apuntar a 4545.

---

## 3. Modelo de membresía (tiers)

### 3.1 Cuotas por bloque — Fase 1 (enforced)

| Tier | Gas por bloque | % del block gas limit | Comentario |
|---|---|---|---|
| `NONE` (0) | — | 0 % | TX rechazada estrictamente |
| `BASIC` (1) | **500,000** | 0.14 % | ~24 TX simples (21K) |
| `STANDARD` (2) | **5,000,000** | 1.43 % | 10× BASIC |
| `PREMIUM` (3) | **10,000,000 + sobrante** | 2.86 % mín | 20× BASIC + sobrante |
| `WHITELISTED` (4) | sin límite | hasta 100 % | relay, admins |

**Suma fija = 15.5M / 350M = 4.43 %** del block gas limit. El **95.57 % restante (334.5M)** es
*sobrante del bloque*: gas que ninguna otra cuenta consumió y que **sólo cuentas PREMIUM** pueden
usar.

#### Regla del sobrante (overflow) para PREMIUM

Si una cuenta PREMIUM excede su cuota base de 10M en un bloque, el selector evalúa si la TX cabe
en el sobrante del bloque:

```
limiteEfectivoPremium(t) = 10_000_000 + (350_000_000 - sum(gasUsedByAllSenders en este bloque))
```

En el caso extremo (PREMIUM es el único sender del bloque), una TX puede consumir hasta **350M
gas en un solo bloque** — equivalente a uno de los contratos Solidity más grandes que existen.

### 3.2 Cuotas mensuales — Fase 2 (planeado)

Con block period 2s y mes de 30 días:

- Bloques/día: **43,200**
- Bloques/mes: **1,296,000**

| Tier | Por bloque | Por día | **Por mes** | TX ERC-20 equivalentes (50K gas c/u) |
|---|---|---|---|---|
| BASIC | 500 K | 21.6 G | **648 G** (6.48 × 10¹¹) | ~12.96 M TX |
| STANDARD | 5 M | 216 G | **6.48 T** (6.48 × 10¹²) | ~129.6 M TX |
| PREMIUM (mín, sin sobrante) | 10 M | 432 G | **12.96 T** (1.30 × 10¹³) | ~259.2 M TX |
| PREMIUM (techo, todo el bloque) | 350 M | 15.12 T | ~453.6 T | ~9 B TX |

**Convención**: G = giga (10⁹), T = tera (10¹²). Son cantidades de gas units, no wei.

**Implicaciones de pricing**: la relación STANDARD/BASIC es 10× — pricing limpio y lineal.
PREMIUM tiene un **piso predecible** (12.96 T) y un **techo elástico** que sube cuando la red
está ociosa.

### 3.3 Tier NONE — cuenta sin membresía

**Estricto**: toda TX cuyo `sender` tiene `Tier.NONE` se rechaza con el mensaje:

```
sender has no active membership
```

Onboarding obligatorio antes de operar. El cliente que reciba este error tiene que registrar la
cuenta en el `MembershipRegistry` antes de reintentar.

### 3.4 Tier WHITELISTED — bypass

Cuentas con `Tier.WHITELISTED` no tienen cuota — bypass completo del enforcement. Pensado para:

- **Relay** de meta-transacciones (la única cuenta que firma puede pagar por miles de usuarios sin
  pegarle al per-block limit del relay mismo).
- **Cuentas admin** de emergencia (rotaciones, hotfix de contratos, intervenciones operativas).

Vive **on-chain como un valor del enum**, no en config del nodo. Implicación práctica: el owner
del contrato puede agregar/quitar whitelisted en caliente, sin tocar la config de los validadores.

---

## 4. Comportamiento ante violaciones

| Violación | Comportamiento | Penalización persistente | Fase |
|---|---|---|---|
| `txGasLimit > tierQuota` (BASIC/STANDARD) | TX rechazada al `eth_sendRawTransaction` con `"tx gasLimit exceeds tier quota"` | **Sí — descarte permanente del pool**. La TX por sí sola excede la cuota; no podría caber en ningún bloque futuro. | Fase 1.5 |
| `used + txGasLimit > tierQuota` (con `txGasLimit ≤ quota`) | TX rechazada por el selector con `"excedió límite de gas en el bloque"` | **Ninguna** — la TX queda en el txpool y se reintenta en bloque siguiente | Fase 1 |
| Excede cuota **mensual** | Cuenta bloqueada **5 min** (~150 bloques) | Sí — todas las TX de la cuenta rechazadas durante el bloqueo, sin importar tier | Fase 2 |
| `Tier.NONE` (sin membresía) | TX rechazada con `"sender has no active membership"` (validator al submit + selector al armar) | **Sí — descarte permanente del pool** | Fase 1 |

### Diseño clave: por qué per-block NO penaliza

El exceso per-block puede ocurrir por **uso legítimo intenso**: cierre de mes, deploy importante,
batch operativo. Penalizar a una cuenta BASIC por intentar 600K en un bloque sería excesivo — el
mecanismo correcto es simplemente decirle "no entrás en este bloque, probá en el próximo".

El bloqueo de 5 min (Fase 2) sí penaliza porque indica **violación contractual del SLA**: ya
consumiste tu cuota mensual contratada y estás pidiendo más de lo pagado.

### Distinción técnica: `invalid` vs `invalidTransient`

El plugin usa la API de Besu en dos modos según la naturaleza del rechazo:

- `TransactionSelectionResult.invalid(...)` para `NONE` **y** para `txGasLimit > tierQuota` → **descarta la TX del pool**. La cuenta no es bienvenida, o la TX nunca cabría — no tiene sentido reintentar.
- `TransactionSelectionResult.invalidTransient(...)` para exceso per-block (`used + txGasLimit > quota` con `txGasLimit ≤ quota`) → **mantiene la TX en el pool**. Reintenta automáticamente en el bloque siguiente cuando los contadores se resetean.

### Fase 1.5: rechazo upfront al admitir al pool

Con Fase 1 sola, las TXs de los casos permanentes (NONE, `txGasLimit > tierQuota`) entraban al
txpool y solo el selector las descartaba después. El cliente JSON-RPC no se enteraba en tiempo
real — tenía que pollear hasta darse cuenta.

Fase 1.5 agrega un `PluginTransactionPoolValidator` (`GasMembershipTransactionValidator`) que
chequea estos mismos casos **al admitir la TX al pool**: `eth_sendRawTransaction` devuelve error
directo y el cliente recibe excepción inmediata.

El selector se mantiene sin cambios funcionales (sigue siendo la red de seguridad y la única
defensa para los casos `invalidTransient`). Detalle en
[`05-fase-1.5-validator-y-pruebas.md`](./05-fase-1.5-validator-y-pruebas.md).

---

## 5. Smart contract de membresía

El plugin lee el tier de cada cuenta vía `eth_call` interno (sin red) al contrato
**`MembershipRegistry`**, deployado en la misma red. La whitelist vive **dentro del enum** del
contrato, no en config del nodo.

### Interfaz

```solidity
interface IMembershipRegistry {
    enum Tier { NONE, BASIC, STANDARD, PREMIUM, WHITELISTED }

    event TierAssigned(address indexed account, Tier tier);
    event TierRevoked(address indexed account);

    function getTier(address account) external view returns (Tier);
    function setTier(address account, Tier tier) external;            // owner only
    function setTierBatch(address[] calldata accounts, Tier[] calldata tiers) external; // owner only
    function removeMember(address account) external;                  // owner only
}
```

Detalles de implementación y tests en [`03-contratos.md`](./03-contratos.md).

### Decisiones de producto cerradas

| Aspecto | Decisión | Razón |
|---|---|---|
| Whitelist en config vs on-chain | **On-chain** (`Tier.WHITELISTED`) | Cambio en caliente sin reiniciar validadores |
| Modelo de admin | **Owner único** (OpenZeppelin `Ownable`) | Multisig se evalúa en producción, no en MVP |
| Expiración de tiers | **No** en MVP | El owner gestiona altas/bajas manualmente; expiración temporal se evalúa en Fase 2 |
| Eventos de auditoría | **Sí, obligatorios** (`TierAssigned`, `TierRevoked`) | Para auditoría externa y reconciliación con backend de billing |

---

## 6. Cómo el plugin consulta el contrato

### Mecanismo: `TransactionSimulationService` interno

El plugin usa el `TransactionSimulationService` del plugin API de Besu para hacer `eth_call`
internamente — sin pasar por el JSON-RPC del propio nodo. Ventajas:

- **Más rápido** (no hay roundtrip de red).
- **Resiliente**: si el JSON-RPC del nodo está caído (mantenimiento, restart), el plugin sigue
  funcionando.
- **Sin dependencia** de Web3j ni de un cliente HTTP en el plugin.

Detalles de implementación en [`02-arquitectura.md § MembershipContractClient`](./02-arquitectura.md#23-membershipcontractclient).

### Cache de tier — TTL = 50 bloques

Sin cache, el plugin haría un `eth_call` por cada TX que evalúa — con cientos de TX/s la
simulación se vuelve cuello de botella. El cache es **obligatorio**:

- Estructura: `Map<Address, (Tier, blockNumber)>`
- Vigencia: una entry es válida si `currentBlock - cachedAt ≤ 50` (≈ 1.6 min con block period 2s)
- Boundary inclusivo: en el bloque 150 una entry cacheada en bloque 100 sigue válida; en el 151 ya expiró.
- Anti-stampede: si N threads piden la misma cuenta cuando la entry está fría, sólo uno ejecuta
  el `eth_call`.

**Tradeoff del TTL elegido (50 bloques)**: balance entre carga sobre el contrato y latencia para
reflejar upgrades de tier:

| TTL | Carga | Latencia para reflejar upgrade |
|---|---|---|
| 10 bloques (~20s) | 5× | ~20s |
| **50 bloques (~1.6 min)** | **1×** | **~1.6 min** |
| 200 bloques (~6.6 min) | 0.25× | ~6.6 min |

Con upgrades poco frecuentes (días/semanas), 1.6 min es invisible para el usuario y aceptable.

---

## 7. Configuración del plugin

Todas las propiedades viven bajo el namespace `ratelimit.*`. Se pasan como **system properties
JVM** vía `BESU_OPTS` o equivalente.

```properties
# === Cuotas por bloque (Fase 1) — gas units ===
ratelimit.gas.basic=500000
ratelimit.gas.standard=5000000
ratelimit.gas.premium=10000000

# === Cache de tier ===
# TTL en bloques. 50 ≈ 1.6 min con block period 2s.
ratelimit.tierCacheTtlBlocks=50

# === Smart contract MembershipRegistry ===
# OBLIGATORIO. El plugin no arranca sin esto.
ratelimit.membershipContract=0xCONTRACT_ADDRESS

# === Opcional: solo si se usa el fallback HTTP en vez de TransactionSimulationService ===
# ratelimit.nodeUrl=http://localhost:4545

# === Cuota mensual (Fase 2 — placeholders, todavía no enforced) ===
# ratelimit.gas.monthly.basic=648000000000
# ratelimit.gas.monthly.standard=6480000000000
# ratelimit.gas.monthly.premium=12960000000000
# ratelimit.monthlyBlockDurationSeconds=300
```

**Reglas de validación** (en el constructor del config):

- `ratelimit.membershipContract` **obligatorio** y con formato `0x` + 40 hex chars.
- `ratelimit.gas.*` valores **enteros positivos** (rechaza 0 y negativos).
- `ratelimit.tierCacheTtlBlocks` **entero positivo**.
- Cualquier valor mal formado → el plugin **falla al arrancar** con `IllegalArgumentException`
  visible en logs. Esto es deliberado: preferimos crashear visible que silenciar enforcement.

**La whitelist NO está acá** — vive on-chain como `Tier.WHITELISTED`.

### Ejemplo de uso (cuando arrancás Besu con el plugin)

```bash
export BESU_OPTS="-Dratelimit.membershipContract=0x8464135c8F25Da09e49BC8782676a84730C318bC"
./node/start-besu.sh
```

Los defaults de las cuotas y el TTL ya están en el código (los valores que ves arriba); sólo
`ratelimit.membershipContract` es obligatorio.

---

## 8. Estrategia en dos fases

### Fase 1 — Plugin enforcing per-block (cerrada)

**Qué incluye**:
- Cuota por bloque por tier (BASIC, STANDARD, PREMIUM, WHITELISTED, NONE).
- Sobrante para PREMIUM.
- Rechazo de TX sin penalización persistente.
- Contrato `MembershipRegistry` deployado y consultado en cada decisión.

**Qué NO incluye** (deliberadamente):
- `BlockedAddressRegistry` — no hay bloqueo temporal en Fase 1.
- Contador mensual on-chain — sin persistencia entre bloques.
- Mecanismo de pagos / billing.

### Fase 2 — Cuota mensual + bloqueo (planeado)

**Componentes nuevos**:
1. **Contrato `UsageMeter.sol`**: `recordUsage(account, gas, periodId)` y
   `getRemainingQuota(account)`. Almacena el consumo acumulado por cuenta y período.
2. **Block listener en el plugin**: post-bloque, recolecta el gas consumido por sender y emite un
   commit batch al `UsageMeter` (cada N bloques para amortizar costo).
3. **`BlockedAddressRegistry`**: lista en memoria con TTL 5 min. Cuando una cuenta supera su
   cuota mensual, se agrega y todas sus TX se rechazan por 5 min.
4. **Extensión del selector**: antes de evaluar quota per-block, consulta
   `UsageMeter.getRemainingQuota(sender)`. Si la TX no cabe en el remaining, se rechaza Y se
   marca como bloqueada.

**Decisiones pendientes para Fase 2**:

- ¿Cómo se define "el mes"? UTC calendar / 30-day rolling / desde la fecha de alta de la cuenta.
- Cadencia de commits batch del block listener al `UsageMeter` (cada bloque / cada 100 bloques /
  cada minuto).
- ¿El owner del contrato pasa a multisig en producción?

Estas decisiones no bloquean Fase 1.

---

## 9. Decisiones cerradas {#decisiones-cerradas}

| Pregunta | Decisión | Fase |
|---|---|---|
| Block gas limit | **350,000,000** (verificado on-chain) | — |
| chainId | **650540** | — |
| Tier NONE | Rechazar TX estrictamente | 1 |
| Cuotas por bloque | BASIC=500K, STANDARD=5M, PREMIUM=10M + sobrante | 1 |
| Cuotas mensuales | BASIC=648G, STANDARD=6.48T, PREMIUM=12.96T mín | 2 (cálculo, sin enforce) |
| Penalización per-block | Ninguna persistente — `invalidTransient` | 1 |
| Penalización per-month | Bloqueo 5 min — `invalid` durante ventana | 2 |
| Cache TTL del tier | 50 bloques (~1.6 min) | 1 |
| Whitelist | On-chain como `Tier.WHITELISTED` (no lista en config) | 1 |
| Admin del contrato | Owner único (OpenZeppelin `Ownable`) | 1 |
| Expiración de tiers | No en MVP | 1 |
| Mecanismo de eth_call | `TransactionSimulationService` interno | 1 |

### Pendientes (no bloquean Fase 1)

- Definición exacta de "mes" para Fase 2.
- Esquema de commits batch del block listener al `UsageMeter`.
- ¿Multisig para owner en producción?

---

## 10. Contexto adicional

- En la red ya corre un **plugin de permisos de TX** separado y funcional. Este plugin de gas es
  **independiente** — ambos se registran como plugins distintos en el mismo SPI.
- Stack del equipo: Java 21, Spring Boot (servicios externos), Web3j 4.12.1, GCP, Kubernetes.
- El binario Besu en producción tiene soporte Falcon-512 (requerido por el campo `falcon512block`
  del genesis). Localmente: `/Users/edumar111/tools/besu-25.8.0-falcon/bin/besu`.

---

*Cuando alguna decisión de este documento cambie, actualizá la sección 9 (Decisiones cerradas)
primero. El resto del documento puede quedar inconsistente temporalmente hasta que la
implementación se sincronice.*
