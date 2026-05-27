# 02 — Arquitectura del Plugin

> Cómo está construido internamente el plugin Java: componentes, flujo de validación, gas
> accounting, concurrencia, dependencias.
>
> Si buscás el "qué" (modelo de membresía, cuotas, penalizaciones), ver
> [`01-requisitos-y-logica.md`](./01-requisitos-y-logica.md).

---

## 1. Vista de componentes

### 1.1 Diagrama lógico

```
            ┌─────────────────────────────────────────────────────────┐
            │                  Besu runtime (JVM)                     │
            │                                                          │
   SPI load │   ┌──────────────────────┐                              │
   ─────────┤── │  GasMembershipPlugin │  ◄── ServiceManager           │
            │   │  (BesuPlugin)        │                              │
            │   └──────────┬───────────┘                              │
            │              │ register()                                │
            │              ▼                                            │
            │   ┌──────────────────────────────────────────┐           │
            │   │  GasMembershipTransactionSelectorFactory │           │
            │   │  (1 instance, registrada en              │           │
            │   │   TransactionSelectionService)           │           │
            │   └─────────────────┬────────────────────────┘           │
            │                     │ create(SelectorsStateManager)       │
            │                     ▼  (puede llamarse N veces)           │
            │   ┌──────────────────────────────────────────┐           │
            │   │  GasMembershipTransactionSelector        │           │
            │   │  (PluginTransactionSelector)             │           │
            │   │                                          │           │
            │   │  ├── evaluateTransactionPreProcessing    │           │
            │   │  └── evaluateTransactionPostProcessing   │           │
            │   └─────────────────┬────────────────────────┘           │
            │                     │ usa (compartidos via factory)       │
            │      ┌──────────────┼──────────────┬──────────────┐      │
            │      ▼              ▼              ▼              ▼      │
            │  ┌────────┐  ┌─────────────┐ ┌────────────┐ ┌────────┐  │
            │  │ Tier   │  │ TierCache   │ │ BlockGas   │ │Contract│  │
            │  │ Cache  │  │  (50 bloq)  │ │ Tracker    │ │ Client │  │
            │  └────────┘  └─────────────┘ └────────────┘ └────┬───┘  │
            │                                                   │      │
            │                                                   ▼      │
            │                       ┌──────────────────────────────┐  │
            │                       │  TransactionSimulationService│  │
            │                       │  (provisto por Besu)         │  │
            │                       └──────────────┬───────────────┘  │
            │                                      │ eth_call interno  │
            │                                      ▼                    │
            │                    ┌─────────────────────────────────┐   │
            │                    │  MembershipRegistry on-chain    │   │
            │                    │  (Solidity)                     │   │
            │                    └─────────────────────────────────┘   │
            └─────────────────────────────────────────────────────────┘
```

### 1.2 Layout de archivos

```
plugin/
├── build.gradle               ← Java 21, shadow, deps compileOnly (no se empaquetan)
├── settings.gradle
├── gradle.properties          ← apunta a /usr/local/opt/openjdk@21 (Homebrew keg-only)
├── gradlew, gradlew.bat       ← wrapper (versión Gradle 8.10.2)
└── src/
    ├── main/
    │   ├── java/com/lacnet/besu/gas/
    │   │   ├── GasMembershipPlugin.java         ← BesuPlugin entry point
    │   │   ├── config/GasMembershipConfig.java  ← system properties → objeto validado
    │   │   ├── model/Tier.java                  ← enum 5 valores con on-chain mapping
    │   │   ├── client/MembershipContractClient.java  ← eth_call → Tier
    │   │   ├── cache/TierCache.java             ← Map<Address,Tier> + TTL bloques
    │   │   ├── tracker/BlockGasTracker.java     ← gas/sender por bloque
    │   │   ├── selector/
    │   │   │   ├── GasMembershipTransactionSelector.java        ← decisión al armar bloque
    │   │   │   └── GasMembershipTransactionSelectorFactory.java ← devuelve selectors
    │   │   └── validator/                                            ← Fase 1.5
    │   │       ├── GasMembershipTransactionValidator.java        ← decisión al admitir al pool
    │   │       └── GasMembershipTransactionValidatorFactory.java ← devuelve validators
    │   └── resources/META-INF/services/
    │       └── org.hyperledger.besu.plugin.BesuPlugin   ← SPI: 1 línea con FQN
    └── test/java/com/lacnet/besu/gas/                   ← espejo de main/
        └── (69 tests JUnit 5 + Mockito)
```

---

## 2. Componentes en detalle

### 2.1 `GasMembershipPlugin` (entry point)

Implementa `org.hyperledger.besu.plugin.BesuPlugin`. Es lo que Besu descubre por SPI desde
`META-INF/services/org.hyperledger.besu.plugin.BesuPlugin`.

**Responsabilidades**:

1. En `register(ServiceManager)`:
   - Carga el config desde system properties (falla early si está mal).
   - Pide al `ServiceManager` los servicios requeridos:
     - `TransactionSimulationService` (para `eth_call` interno).
     - `TransactionSelectionService` (para registrar el selector factory).
     - `TransactionPoolValidatorService` (para registrar el validator factory — Fase 1.5).
     - Si cualquiera no está disponible → `IllegalStateException` con mensaje preciso.
   - Construye el grafo: `MembershipContractClient` + `TierCache` + `BlockGasTracker` + selector factory + validator factory. **`TierCache` se comparte entre selector y validator** — un solo lookup al contrato por `(sender, bloque)` sirve a ambos puntos de decisión.
   - Registra ambos factories:
     - `selection.registerPluginTransactionSelectorFactory(selectorFactory)`
     - `validation.registerPluginTransactionValidatorFactory(validatorFactory)`
2. `start()`, `stop()` — solo logs informativos. Todo el cableado vive en `register`.

**Diseño clave — fail-fast en arranque**: si la config está mal (falta el contractAddress,
quotas negativas, TTL inválido), el plugin **crashea visible** en arranque. La alternativa
(silenciosamente no enforzar nada) sería desastrosa porque parecería que el plugin está activo
pero las TXs pasarían sin control.

**Constructor inyectable**: el plugin tiene dos constructores:

```java
public GasMembershipPlugin() { this(System::getProperty); }     // SPI usa éste
GasMembershipPlugin(Function<String,String> propertyResolver)   // tests usan éste
```

Esto permite testear `register()` sin contaminar `System.setProperty`.

### 2.2 `GasMembershipTransactionSelectorFactory`

Implementa `PluginTransactionSelectorFactory` del plugin API. Besu puede llamar a `create()`
**múltiples veces** (una por ronda de selección, por proposer paralelo, etc.).

**Decisión crítica — estado compartido**: las dependencias `TierCache` y `BlockGasTracker`
viven **una sola vez en el factory** y se pasan a todos los selectors creados. Si cada selector
tuviera su propio tracker, perderíamos la contabilidad de gas por bloque entre llamadas.

```java
public PluginTransactionSelector create(SelectorsStateManager stateManager) {
    return new GasMembershipTransactionSelector(config, client, cache, tracker);
    //                                                          └── compartidos ──┘
}
```

### 2.3 `MembershipContractClient`

Encapsula la consulta on-chain del tier. **No cachea** — el caching es responsabilidad de
`TierCache`.

**Mecanismo**: `TransactionSimulationService.simulate(CallParameter, ...)` del plugin API. Esto
es un `eth_call` **interno** que no pasa por el JSON-RPC del nodo — más rápido y resiliente a
caídas del RPC.

**ABI encoding manual**:

```java
private static final Bytes GET_TIER_SELECTOR = Bytes.fromHexString("0xb45aae52");
// = keccak256("getTier(address)")[0:4]

Bytes payload = Bytes.concatenate(GET_TIER_SELECTOR, Bytes32.leftPad(account));
// calldata: 4 bytes selector + 32 bytes (address left-padded)
```

El selector está **hardcoded** en vez de computarlo en runtime para evitar dependencia de Bouncy
Castle. Se verificó offline con jshell + BC. Un test Solidity en `MembershipRegistry.t.sol`
verifica que `IMembershipRegistry.getTier.selector == 0xb45aae52` — si la firma cambia, el test
Solidity falla y avisa que hay que actualizar el plugin.

**Robustez (tolerancia a fallos)**:

| Caso | Comportamiento |
|---|---|
| `simulator.simulate()` lanza | Loguea WARN y devuelve `Tier.NONE` |
| Devuelve `Optional.empty()` | Devuelve `Tier.NONE` |
| `result.isInvalid()` | Devuelve `Tier.NONE` |
| `result.isSuccessful() == false` | Devuelve `Tier.NONE` |
| Output bytes vacíos | Devuelve `Tier.NONE` |
| Valor on-chain fuera de rango (≥5) | Loguea WARN y devuelve `Tier.NONE` |

**Por qué NONE en cualquier fallo**: en caso de duda, no se enforza membresía. La cuenta no opera
hasta que el plugin pueda confirmar su tier. Es el modo seguro: nunca aprobamos una TX por error
de comunicación.

**`CallParameter` implementado como clase privada `ReadOnlyCall`**: la interface tiene 15
métodos, casi todos devuelven `Optional.empty()`. Lo implementamos a mano para evitar acoplarnos
a `ImmutableCallParameter` que vive en `besu-ethereum-core` (paquete internal).

**Flags de simulación**:

```java
EnumSet.of(
    SimulationParameters.ALLOW_EXCEEDING_BALANCE,  // sender sintético sin balance
    SimulationParameters.ALLOW_FUTURE_NONCE,       // sin nonce real
    SimulationParameters.ALLOW_UNDERPRICED         // gas price 0
);
```

Sin esto, una read-only call falla por checks que sólo aplican a TXs reales.

### 2.4 `TierCache`

Cache `Map<Address, (Tier, blockNumber)>` con TTL en bloques.

**API**:

```java
Optional<Tier> get(Address account, long currentBlock)
void put(Address account, Tier tier, long currentBlock)
Tier getOrLoad(Address account, long currentBlock, Function<Address, Tier> loader)
```

El selector usa `getOrLoad`, que implementa **cache-aside con anti-stampede**:

```java
public Tier getOrLoad(Address account, long currentBlock, Function<Address,Tier> loader) {
    CacheEntry refreshed = entries.compute(account, (key, existing) -> {
        if (existing != null && !isExpired(existing, currentBlock)) return existing;
        return new CacheEntry(loader.apply(key), currentBlock);
    });
    return refreshed.tier();
}
```

**Decisión crítica — `compute()` no `computeIfAbsent()`**: `computeIfAbsent` solo invoca el
mapping function si la key está ausente. Si la entry expirada está presente, NO recargaría.
Necesitamos evaluar la vigencia DENTRO de la lambda y decidir refresh ahí. `compute()` permite
eso.

**Anti-stampede**: bajo `compute()`, N threads que piden la misma cuenta cuando la cache está
fría se serializan **solo para esa key** — el loader corre una vez. Para keys distintas, los
loaders corren en paralelo. Cubierto por dos tests:

- `getOrLoadConcurrenteConMismaKeyEjecutaLoaderUnaVez` — 20 threads, 1 call al loader.
- `getOrLoadConcurrenteConKeysDistintasNoSeSerializa` — 16 threads, 16 keys distintas → 16 calls.

**Boundary del TTL**: entry vigente si `currentBlock - cachedAt ≤ ttlBlocks`. Una entry cacheada
en bloque 100 con TTL=50 sigue válida en bloque 150 y expira en 151.

### 2.5 `BlockGasTracker`

Acumula gas consumido por sender en el bloque actual. Se resetea al cambiar el bloque.

**Estado**:

```java
private volatile long currentBlockNumber = -1L;             // sentinel
private final ConcurrentHashMap<Address, AtomicLong> gasBySender;
```

**API**:

```java
synchronized void onBlockChange(long blockNumber)  // resetea si cambió
long getUsed(Address sender)                       // gas usado por sender
long totalUsed()                                   // suma de todos (para sobrante PREMIUM)
void add(Address sender, long gas)                 // suma al contador del sender
```

**Concurrencia híbrida**:

- `onBlockChange` es `synchronized` — el reset es raro (cada ~2s) pero debe ser atómico.
- `add()` es **lock-free** vía `AtomicLong.addAndGet` — corre en paralelo con otros adds, hot
  path.
- `getUsed()` y `totalUsed()` también lock-free (lecturas atómicas).

**`totalUsed()` puede subestimar bajo carga concurrente**: si un thread está haciendo
`add(sender, X)` mientras otro hace `totalUsed()`, el segundo puede ver el contador antes de la
suma. **Esto es seguro**: produce un sobrante PREMIUM **conservador** (menor del real), lo cual
es estrictamente menos permisivo. Lo opuesto (sobreestimar) permitiría a PREMIUM exceder el block
gas limit real — peligroso.

**Validaciones**:

- `add(sender, 0)` es no-op (no crea entry).
- `add(sender, negativo)` lanza `IllegalArgumentException` (indica bug del caller).

### 2.6 `GasMembershipTransactionSelector` (decisión al armar bloque)

Implementa `PluginTransactionSelector`. Es el componente que ejecuta el flujo de validación
**durante la construcción de cada bloque**.

**Métodos del API**:

| Método | Cuándo lo llama Besu | Qué hace acá |
|---|---|---|
| `evaluateTransactionPreProcessing` | Antes de procesar la TX | Decide si la TX es elegible: SELECTED o INVALID con razón |
| `evaluateTransactionPostProcessing` | Después de procesarla | Contabiliza el `gasUsed` real en el tracker |
| `getOperationTracer` | (default no-op) | No usado |
| `onTransactionSelected`/`NotSelected` | (defaults no-op) | No usados |

Flujo completo en § 3.

### 2.7 `GasMembershipTransactionValidator` (decisión al admitir al pool — Fase 1.5)

Implementa `PluginTransactionPoolValidator` (del `txvalidator` package del plugin API).
Se ejecuta **al admitir cada TX al txpool**, antes de que el selector la vea — es decir,
durante `eth_sendRawTransaction`.

**Por qué existe**: sin validator, Besu acepta la TX al pool y solo el selector la rechaza
*después* (al armar el siguiente bloque). El cliente no recibe error inmediato del RPC: tiene
que pollear si la TX se descartó o se minó. Con el validator, las TXs **estructuralmente
imposibles** son rechazadas en el RPC mismo y ethers tira excepción en milisegundos.

**Diferencia con el selector**:

| | Selector | Validator |
|---|---|---|
| Cuándo corre | Al armar cada bloque | Al admitir al txpool |
| Contexto disponible | `ProcessableBlockHeader`, gas usado per-block, sobrante del bloque | Sólo la `Transaction` |
| Resultados posibles | `SELECTED`, `INVALID`, `INVALID_TRANSIENT` | `Optional<String>` (vacío = válida, string = razón de error) |
| Lo que decide | Si la TX cabe en ESTE bloque (cualquier criterio) | Si la TX puede caber EN ALGÚN bloque futuro |

**Lógica**:

| Caso | Decisión validator | Razón |
|---|---|---|
| `WHITELISTED` | válida | Pasa siempre. |
| `PREMIUM` | válida | El selector usa el sobrante del bloque — el validator no lo conoce. |
| `NONE` | inválida | `REASON_NO_MEMBERSHIP` — misma constante que el selector. |
| `BASIC/STANDARD` + `txGasLimit > tierQuota` | inválida | `REASON_TX_EXCEEDS_TIER_QUOTA` — TX no cabe en ningún bloque. |
| `BASIC/STANDARD` + `txGasLimit ≤ tierQuota` | válida | Selector decide con accounting per-block (puede ser `invalidTransient`). |

**Cache compartida**: usa la misma `TierCache` que el selector. Para el block key del cache,
toma el pending block number del `TransactionSimulationService.simulatePendingBlockHeader()`
(mismo header que ya usa internamente `MembershipContractClient`).

**Lo que NO chequea**:

- `used + txGasLimit > quota` (depende del estado per-block que el validator no tiene).
- PREMIUM con `txGasLimit > blockGasLimit` (el block gas limit puede cambiar; siempre delega al selector).

### 2.8 `GasMembershipConfig`

Carga lazy desde system properties (prefijo `ratelimit.*`).

**API**:

```java
static GasMembershipConfig fromSystemProperties()                   // runtime
static GasMembershipConfig fromProperties(Function<String,String>)  // tests
long getQuotaPerBlock(Tier tier)
int getTierCacheTtlBlocks()
String getMembershipContractAddress()
Optional<String> getNodeUrl()
```

**EnumMap precargado** con todas las quotas:

```java
quotas.put(Tier.NONE, 0L);
quotas.put(Tier.BASIC, basic);
quotas.put(Tier.STANDARD, standard);
quotas.put(Tier.PREMIUM, premium);
quotas.put(Tier.WHITELISTED, Long.MAX_VALUE);  // bypass
```

`getQuotaPerBlock(WHITELISTED) = Long.MAX_VALUE` permite tratar WHITELISTED como cualquier otro
tier en la comparación, **sin rama especial** en el selector. La condición
`gasUsedSoFar + tx.gasLimit ≤ quota` nunca dispara overflow porque `Long.MAX_VALUE` es enorme.
Más simple que una guarda `if (tier == WHITELISTED) return SELECTED` separada y igualmente
eficiente.

**Validación eager** en el constructor:
- contract address: `0x` + 40 hex chars (regex).
- gas > 0, TTL > 0.
- nodeUrl: opcional.

### 2.9 `Tier` (enum)

Cinco valores con `onChainValue()` explícito:

```java
public enum Tier {
    NONE(0), BASIC(1), STANDARD(2), PREMIUM(3), WHITELISTED(4);

    public int onChainValue();
    public static Tier fromOnChainValue(int value);  // throws si no es 0..4
}
```

**Por qué `onChainValue()` y no `ordinal()`**: si alguien reordena el enum Java sin actualizar el
contrato (o viceversa), `ordinal()` daría decodificación silenciosa incorrecta. Con
`onChainValue()` explícito + tests, un reorder rompe los tests inmediatamente.

Hay un **test cross-stack** (en `TierTest.onChainValuesMatchSolidityEnumOrder`) que verifica los
valores, y otro en Solidity (`test_OnChainEnumValuesMatchPluginExpectation`) que verifica el
otro lado. Si los dos lados desincronizan, alguno de los dos tests falla.

---

## 3. Flujo de validación por TX

### 3.1 Pre-processing (decisión)

```
evaluateTransactionPreProcessing(TransactionEvaluationContext ctx):

  ┌──────────────────────────────────────────────────────┐
  │ blockNumber  = ctx.getPendingBlockHeader().getNumber │
  │ sender       = ctx.getPendingTransaction().getTransaction().getSender() │
  │ txGasLimit   = ctx.getPendingTransaction().getTransaction().getGasLimit() │
  │ blockGasLimit = ctx.getPendingBlockHeader().getGasLimit() │
  └────────────────────────┬─────────────────────────────┘
                           │
                           ▼
                tracker.onBlockChange(blockNumber)
                       (idempotente)
                           │
                           ▼
       tier = cache.getOrLoad(sender, blockNumber, client::getTier)
                           │
                           ▼
              ┌─────────────────────────────┐
              │ tier == WHITELISTED?  ──── YES ─► SELECTED
              └─────────────┬───────────────┘
                            │ NO
                            ▼
              ┌─────────────────────────────┐
              │ tier == NONE?  ──────────── YES ─► invalid("sender has no active membership")
              └─────────────┬───────────────┘
                            │ NO (BASIC, STANDARD o PREMIUM)
                            ▼
       used      = tracker.getUsed(sender)
       quota     = config.getQuotaPerBlock(tier)
       projected = used + txGasLimit
                            │
                            ▼
              ┌─────────────────────────────┐
              │ projected ≤ quota?  ─────── YES ─► SELECTED
              └─────────────┬───────────────┘
                            │ NO (excede)
                            ▼
              ┌─────────────────────────────┐
              │ tier == PREMIUM?  ───── NO ──► invalidTransient("excedió límite de gas en el bloque")
              └─────────────┬───────────────┘
                            │ YES — evaluar sobrante
                            ▼
       leftover = blockGasLimit - tracker.totalUsed()
                            │
                            ▼
              ┌─────────────────────────────┐
              │ txGasLimit ≤ leftover?  ─── YES ─► SELECTED
              └─────────────┬───────────────┘
                            │ NO — ni quota ni sobrante alcanzan
                            ▼
                   invalidTransient("excedió límite de gas en el bloque")
```

### 3.2 Post-processing (contabilización)

```
evaluateTransactionPostProcessing(ctx, processingResult):
    sender   = ctx.getPendingTransaction().getTransaction().getSender()
    gasUsed  = processingResult.getEstimateGasUsedByTransaction()  // real, no estimate
    tracker.add(sender, gasUsed)
    return SELECTED
```

Este método sólo se llama si pre-processing devolvió SELECTED. Si fue INVALID o
INVALID_TRANSIENT, el flujo de Besu no llega acá.

### 3.3 `gasLimit` vs `gasUsed` — la diferencia importa

| Etapa | Información disponible | Qué usamos |
|---|---|---|
| Pre-processing | `tx.gasLimit` (estimación del wallet, conservadora — suele ser mayor que el real) | **Para decidir** (proyección pesimista) |
| Post-processing | `processingResult.getEstimateGasUsedByTransaction()` (gas usado real) | **Para contabilizar** (lo que realmente consumió) |

**Consecuencia práctica**: en pre-processing asumimos el peor caso (todo el gasLimit se usa). Si
la TX termina usando 70% de su gasLimit, la cuota efectiva del sender en ese bloque queda 30%
sub-utilizada respecto del límite estricto. **Tradeoff aceptado** — la única forma segura
de evitar overshoot del bloque.

---

## 4. Modelo de concurrencia

Cuatro escenarios a manejar:

| # | Escenario | Mecanismo |
|---|---|---|
| 1 | Selectors paralelos (Besu llama factory.create() N veces) | Tracker y cache **compartidos** vía factory |
| 2 | Múltiples threads consultan el cache para misma cuenta | `ConcurrentHashMap.compute` serializa por key |
| 3 | Adds concurrentes al tracker para distintos senders | `ConcurrentHashMap<Address, AtomicLong>` lock-free |
| 4 | Reset de bloque mientras hay adds en curso | `synchronized` en `onBlockChange` |

**No hay locks globales en el hot path** (validación de TX). El único `synchronized` es el reset
del tracker, que ocurre una vez cada ~2s.

---

## 5. Dependencias y build

### 5.1 Gradle (build.gradle)

```groovy
plugins {
    id 'java-library'
    id 'com.gradleup.shadow' version '8.3.5'
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories {
    mavenCentral()
    maven {
        name = 'hyperledger-besu'
        url = 'https://hyperledger.jfrog.io/artifactory/besu-maven'
        content {
            includeGroup 'org.hyperledger.besu'
            includeGroup 'org.hyperledger.besu.internal'
        }
    }
}

dependencies {
    compileOnly "org.hyperledger.besu:besu-plugin-api:25.8.0"
    compileOnly "org.hyperledger.besu:besu-datatypes:25.8.0"
    compileOnly "org.hyperledger.besu:besu-evm:25.8.0"           // OperationTracer.NO_TRACING
    compileOnly 'io.consensys.tuweni:tuweni-bytes:2.7.1'         // Bytes / Bytes32

    implementation 'org.slf4j:slf4j-api:2.0.13'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.11.0'
    // En tests sí necesitamos las deps reales (no solo compileOnly):
    testImplementation "org.hyperledger.besu:besu-plugin-api:25.8.0"
    testImplementation "org.hyperledger.besu:besu-datatypes:25.8.0"
    testImplementation "org.hyperledger.besu:besu-evm:25.8.0"
    testImplementation 'io.consensys.tuweni:tuweni-bytes:2.7.1'
}

shadowJar { archiveClassifier.set(''); mergeServiceFiles() }
build.dependsOn shadowJar
```

**Crítico — `compileOnly` para Besu**: el runtime de Besu ya tiene estas clases; bundlearlas en
el JAR del plugin rompe el classloader (clases duplicadas, conflictos de versiones).
`mergeServiceFiles()` en shadow es importante para que el SPI `META-INF/services/...` no se
sobrescriba al fusionar dependencias.

### 5.2 Detalles del repo Maven

- `besu-plugin-api`, `besu-datatypes`, `besu-evm` se publican en
  **`https://hyperledger.jfrog.io/artifactory/besu-maven`** (NO Maven Central).
- Versión publicada: **`25.8.0`** (sin sufijo). El binario local que usamos es `25.8.0.4` (build
  interno), pero el plugin-api es binario-compatible con `25.8.0`.
- `tuweni-bytes` se migró de `org.apache.tuweni` a `io.consensys.tuweni` como groupId en Maven
  Central. El package Java sigue siendo `org.apache.tuweni.bytes.*`.

### 5.3 JDK 21

Besu 25.8 está compilado con class file v65 (Java 21). El nodo y el build del plugin requieren
JDK 21. El proyecto trae `gradle.properties` apuntando a `/usr/local/opt/openjdk@21` (Homebrew
keg-only):

```properties
org.gradle.java.installations.paths=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Si tu JDK 21 está en otra ruta, sobreescribí esa property o exportá
`org.gradle.java.installations.paths` en tu shell.

---

## 6. Carga del plugin por Besu

Besu busca plugins en `${besu.plugins.dir}`, o por default en `${besu.home}/plugins/` (= dir de
instalación de Besu, NO data-path). Nuestro `node/start-besu.sh` setea:

```bash
export JAVA_OPTS="${JAVA_OPTS:-} -Dbesu.logs.dir=${LOGS_DIR} -Dbesu.plugins.dir=${PLUGINS_DIR}"
```

donde `PLUGINS_DIR=node/data/plugins`. Copiando el JAR ahí, el plugin se descubre.

Cuando arranca correctamente, Besu muestra un banner como:

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

Y a continuación, en los logs del plugin:

```
INFO GasMembershipPlugin: register()
INFO GasMembershipPlugin: registrado (contract=0x... ttlBlocks=50 quotas: BASIC=500000 STANDARD=5000000 PREMIUM=10000000)
INFO GasMembershipPlugin: start()
```

Si NO ves estos mensajes, el plugin no se cargó — ver
[`04-smoke-test-e2e.md § Troubleshooting`](./04-smoke-test-e2e.md#troubleshooting).

---

## 7. Tests unitarios

**69 tests totales**, organizados por componente:

| Test class | Tests | Foco |
|---|---|---|
| `TierTest` | 3 | onChainValue mapping, decodificación, rechazo de valores fuera de rango |
| `GasMembershipConfigTest` | 10 | Defaults, overrides, validación (gas > 0, address válida, etc.) |
| `MembershipContractClientTest` | 8 | Decodificación de los 5 tiers, 4 modos de fallo → NONE, ABI encoding correcto |
| `TierCacheTest` | 12 | TTL boundary, hit/miss, **anti-stampede con 20 threads** |
| `BlockGasTrackerTest` | 8 | Reset, acumulación, **adds concurrentes 16×1000 sin pérdida** |
| `GasMembershipTransactionSelectorTest` | 13 | Las 6 ramas del flujo + cambio de bloque + post-processing + cache hit + split de invalid vs invalidTransient |
| `GasMembershipTransactionValidatorTest` | 7 | WHITELISTED/PREMIUM/NONE/BASIC>quota/BASIC≤quota/STANDARD>quota/cache hit (Fase 1.5) |
| `GasMembershipPluginTest` | 8 | SPI discovery, register() happy path + 4 paths de error (incluye `TransactionPoolValidatorService` ausente) |

### Tests con valor especial

- **Tests cross-stack**: `TierTest.onChainValuesMatchSolidityEnumOrder` (verifica 0..4) y
  `MembershipContractClientTest.abiEncodingTieneSelectorYAddressPadded` (verifica el selector
  `0xb45aae52`). Sus contrapartes Solidity en `MembershipRegistry.t.sol` cierran el loop.
- **Tests de concurrencia**: el de `TierCache` con 20 threads y el de `BlockGasTracker` con 16
  threads exponen race conditions reales — son lentos (~500ms) pero valen oro.

### Correr los tests

```bash
cd plugin

# Suite completa (69 tests, ~5s con caché)
./gradlew test

# Una clase específica
./gradlew test --tests "com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorTest"

# Re-correr forzando que no use el caché
./gradlew test --rerun
```

---

## 8. Decisiones de diseño relevantes

| # | Decisión | Razón |
|---|---|---|
| 1 | `compileOnly` para deps de Besu | Las clases las provee el runtime; bundlearlas rompe el classloader |
| 2 | Selector hardcoded `0xb45aae52` | Evita dependencia de Bouncy Castle / tuweni-crypto |
| 3 | `CallParameter` implementado a mano | Evita acoplarse a internals (`ImmutableCallParameter`) |
| 4 | NONE en cualquier fallo del client | "Cuando dudás, no enforces": modo seguro |
| 5 | `compute()` no `computeIfAbsent()` en cache | Permite re-cargar entries expiradas bajo lock por key |
| 6 | `WHITELISTED = Long.MAX_VALUE` quota | Una sola rama de comparación, sin if especial |
| 7 | Fail-fast en arranque si config inválida | Mejor crashear visible que silenciar enforcement |
| 8 | Tracker y cache compartidos en el factory | Múltiples selectors comparten estado por-bloque |

---

## 9. Lecturas relacionadas

- [Requisitos y lógica del plugin](./01-requisitos-y-logica.md) — el "qué".
- [Contratos y tests](./03-contratos.md) — el otro lado del eth_call.
- [Smoke test E2E](./04-smoke-test-e2e.md) — validación de todo este diseño en una red real.
