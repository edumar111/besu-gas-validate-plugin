package com.lacnet.besu.gas;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.client.UsageMeterClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorFactory;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.usage.BlockedAddressRegistry;
import com.lacnet.besu.gas.usage.CommitTrigger;
import com.lacnet.besu.gas.usage.JsonRpcRecorderRpc;
import com.lacnet.besu.gas.usage.MonthlyQuotaGuard;
import com.lacnet.besu.gas.usage.MonthlyUsageTracker;
import com.lacnet.besu.gas.usage.PeriodClock;
import com.lacnet.besu.gas.usage.RecorderRpc;
import com.lacnet.besu.gas.usage.Secp256k1RecorderSigner;
import com.lacnet.besu.gas.usage.TierResolver;
import com.lacnet.besu.gas.usage.UsageBlockListener;
import com.lacnet.besu.gas.usage.UsageCommitter;
import com.lacnet.besu.gas.validator.GasMembershipTransactionValidatorFactory;
import java.math.BigInteger;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.BesuEvents;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.RpcEndpointService;
import org.hyperledger.besu.plugin.services.TransactionPoolValidatorService;
import org.hyperledger.besu.plugin.services.TransactionSelectionService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.exception.PluginRpcEndpointException;
import org.hyperledger.besu.plugin.services.rpc.PluginRpcRequest;
import org.hyperledger.besu.plugin.services.rpc.RpcMethodError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point del plugin. Cablea todos los componentes en {@link #register} y registra:
 * <ul>
 *   <li>{@link GasMembershipTransactionSelectorFactory} contra {@link TransactionSelectionService}
 *       — decide qué TXs entran a cada bloque.</li>
 *   <li>{@link GasMembershipTransactionValidatorFactory} contra
 *       {@link TransactionPoolValidatorService} — rechaza upfront al admitir al txpool (Fase 1.5).</li>
 *   <li>Métodos JSON-RPC {@code gasMembership_*} (Fase 1.6 + Fase 2).</li>
 *   <li>Un {@code BlockAddedListener} que contabiliza el uso mensual y aplica el bloqueo de 5 min
 *       (Fase 2). En el nodo recorder, además arma/firma/envía los commits al {@code UsageMeter}.</li>
 * </ul>
 *
 * <p>Cargado por Besu vía Java SPI. El constructor sin args es obligatorio para {@link java.util.ServiceLoader}.
 */
public class GasMembershipPlugin implements BesuPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(GasMembershipPlugin.class);
    private static final String NAME = "GasMembershipPlugin";

    private final Function<String, String> propertyResolver;

    private GasMembershipConfig config;
    private GasMembershipTransactionSelectorFactory factory;
    private GasMembershipTransactionValidatorFactory validatorFactory;
    private RejectionEventBus eventBus;
    private MonthlyUsageTracker monthlyTracker;
    private BlockedAddressRegistry blockedAddresses;

    // Guardados en register() para completar el wiring de Fase 2 en start() (BesuEvents y
    // BlockchainService recién están disponibles en start(), no en register()).
    private ServiceManager serviceManager;
    private TransactionSimulationService simulator;
    private MembershipContractClient membershipClient;
    private TierCache tierCache;
    private MonthlyUsageTracker.BaselineLoader baselineLoader;
    private boolean monthlyEnforcementEnabled;

    /** Constructor para Java SPI. */
    public GasMembershipPlugin() {
        this(System::getProperty);
    }

    /** Visible para tests — permite inyectar el resolver de properties sin tocar System.setProperty. */
    GasMembershipPlugin(final Function<String, String> propertyResolver) {
        this.propertyResolver = propertyResolver;
    }

    @Override
    public Optional<String> getName() {
        return Optional.of(NAME);
    }

    @Override
    public void register(final ServiceManager serviceManager) {
        LOG.info("{}: register()", NAME);
        this.serviceManager = serviceManager;

        // 1. Cargar y validar config (falla early si está mal).
        this.config = GasMembershipConfig.fromProperties(propertyResolver);

        // 2. Obtener servicios requeridos del runtime de Besu.
        TransactionSimulationService simulator = serviceManager
                .getService(TransactionSimulationService.class)
                .orElseThrow(() -> new IllegalStateException(
                        NAME + ": TransactionSimulationService no disponible "
                                + "(requerido para eth_call al MembershipRegistry)"));
        TransactionSelectionService selection = serviceManager
                .getService(TransactionSelectionService.class)
                .orElseThrow(() -> new IllegalStateException(
                        NAME + ": TransactionSelectionService no disponible "
                                + "(requerido para registrar el selector factory)"));
        TransactionPoolValidatorService validation = serviceManager
                .getService(TransactionPoolValidatorService.class)
                .orElseThrow(() -> new IllegalStateException(
                        NAME + ": TransactionPoolValidatorService no disponible "
                                + "(requerido para registrar el validator factory)"));
        RpcEndpointService rpc = serviceManager
                .getService(RpcEndpointService.class)
                .orElseThrow(() -> new IllegalStateException(
                        NAME + ": RpcEndpointService no disponible "
                                + "(requerido para exponer los métodos gasMembership_*)"));

        // 3. Construir el grafo de objetos base (Fase 1). Cache, tracker, client y eventBus se
        //    comparten entre selector y validator.
        Address contractAddress = Address.fromHexString(config.getMembershipContractAddress());
        MembershipContractClient client = new MembershipContractClient(simulator, contractAddress);
        TierCache cache = new TierCache(config.getTierCacheTtlBlocks());
        BlockGasTracker tracker = new BlockGasTracker();
        this.eventBus = new RejectionEventBus();
        this.simulator = simulator;
        this.membershipClient = client;
        this.tierCache = cache;

        // 4. Fase 2: construir el estado del enforcement mensual (tracker + bloqueo + guard). El
        //    block listener y el committer se cablean en start(), cuando BesuEvents/BlockchainService
        //    ya están disponibles (en register() todavía no lo están). El guard ya puede vivir acá:
        //    el selector/validator lo consultan recién al evaluar TXs (post-start).
        MonthlyQuotaGuard monthlyGuard = buildMonthlyGuard();

        this.factory = new GasMembershipTransactionSelectorFactory(
                config, client, cache, tracker, eventBus, monthlyGuard);
        this.validatorFactory = new GasMembershipTransactionValidatorFactory(
                config, client, cache, simulator, eventBus, monthlyGuard);

        // 5. Registrar selector, validator y los métodos JSON-RPC custom.
        selection.registerPluginTransactionSelectorFactory(factory);
        validation.registerPluginTransactionValidatorFactory(validatorFactory);
        rpc.registerRPCEndpoint("gasMembership", "getRejection", this::handleGetRejection);
        rpc.registerRPCEndpoint("gasMembership", "listRejectionsBySender", this::handleListRejectionsBySender);
        rpc.registerRPCEndpoint("gasMembership", "getMonthlyUsage", this::handleGetMonthlyUsage);

        LOG.info("{}: registrado (contract={} ttlBlocks={} quotas: BASIC={} STANDARD={} PREMIUM={} | "
                        + "mensual: BASIC={} STANDARD={} PREMIUM={} recorder={})",
                NAME,
                config.getMembershipContractAddress(),
                config.getTierCacheTtlBlocks(),
                config.getQuotaPerBlock(Tier.BASIC),
                config.getQuotaPerBlock(Tier.STANDARD),
                config.getQuotaPerBlock(Tier.PREMIUM),
                config.getMonthlyQuota(Tier.BASIC),
                config.getMonthlyQuota(Tier.STANDARD),
                config.getMonthlyQuota(Tier.PREMIUM),
                config.isRecorder());
    }

    /**
     * Construye el estado del enforcement mensual de Fase 2 (tracker + registro de bloqueo + guard).
     * NO toca {@code BesuEvents}/{@code BlockchainService} (no disponibles en register()): el block
     * listener y el committer se registran en {@link #start()}.
     *
     * @return el {@link MonthlyQuotaGuard} compartido por selector y validator
     */
    private MonthlyQuotaGuard buildMonthlyGuard() {
        // periodId inicial: 0 (se corrige al primer bloque vía rollTo en el listener). No leemos el
        // head acá porque BlockchainService puede no estar disponible todavía.
        this.monthlyTracker = new MonthlyUsageTracker(0L);
        this.blockedAddresses = new BlockedAddressRegistry();

        // Baseline loader: rehidrata el uso ya commiteado on-chain desde el UsageMeter (si está
        // configurado). Sin UsageMeter, el baseline es 0 (solo cuenta el uso visto desde el arranque).
        Optional<String> meterAddr = config.getUsageMeterContractAddress();
        if (meterAddr.isPresent()) {
            UsageMeterClient meterClient = new UsageMeterClient(
                    simulator, Address.fromHexString(meterAddr.get()));
            this.baselineLoader = meterClient::getUsage;
        } else {
            this.baselineLoader = (period, account) -> 0L;
        }

        this.monthlyEnforcementEnabled = true;
        return new MonthlyQuotaGuard(
                config, monthlyTracker, blockedAddresses, baselineLoader, Clock.systemUTC());
    }

    /**
     * Registra el {@link UsageBlockListener} (y, en el recorder, el {@link UsageCommitter}) usando
     * {@code BesuEvents}/{@code BlockchainService}, que recién están disponibles en {@link #start()}.
     * Si {@code BesuEvents} no está, Fase 2 queda desactivada (degradación elegante a per-block).
     */
    private void startMonthlyEnforcement() {
        if (!monthlyEnforcementEnabled) {
            return;
        }
        Optional<BesuEvents> events = serviceManager.getService(BesuEvents.class);
        if (events.isEmpty()) {
            LOG.warn("{}: BesuEvents no disponible — Fase 2 (cuota mensual) DESACTIVADA; "
                    + "solo enforcement per-block", NAME);
            return;
        }

        TierResolver tierResolver = (account, blockNumber) ->
                tierCache.getOrLoad(account, blockNumber, membershipClient::getTier);

        CommitTrigger commitTrigger = maybeBuildCommitter();

        UsageBlockListener listener = new UsageBlockListener(
                config, monthlyTracker, blockedAddresses, tierResolver, baselineLoader,
                Clock.systemUTC(), commitTrigger);
        events.get().addBlockAddedListener(listener);

        LOG.info("{}: Fase 2 activa (meterContract={} recorder={})",
                NAME, config.getUsageMeterContractAddress().orElse("<none>"), config.isRecorder());
    }

    /**
     * Construye el {@link UsageCommitter} si este nodo es el recorder. Devuelve {@code null} en los
     * nodos no-recorder (el block listener simplemente no dispara commits).
     */
    private CommitTrigger maybeBuildCommitter() {
        if (!config.isRecorder()) {
            return null;
        }
        // La config ya validó (eager) que recorder ⇒ recorderKey + meterContract + nodeUrl presentes.
        Address meter = Address.fromHexString(config.getUsageMeterContractAddress().orElseThrow());
        long chainId = serviceManager.getService(BlockchainService.class)
                .flatMap(BlockchainService::getChainId)
                .map(BigInteger::longValueExact)
                .orElseThrow(() -> new IllegalStateException(
                        NAME + ": chainId no disponible (requerido para firmar el commit del recorder)"));
        Secp256k1RecorderSigner signer = new Secp256k1RecorderSigner(config.getRecorderKey().orElseThrow());
        RecorderRpc recorderRpc = new JsonRpcRecorderRpc(config.getNodeUrl().orElseThrow());
        UsageCommitter committer = new UsageCommitter(
                monthlyTracker, meter, chainId, config.getRecorderGasLimit(),
                signer, recorderRpc, org.hyperledger.besu.crypto.Hash::keccak256);
        LOG.info("{}: nodo RECORDER — commits a UsageMeter {} con cuenta {} (chainId={})",
                NAME, meter.toHexString(), signer.address().toHexString(), chainId);
        return committer;
    }

    // === Handlers JSON-RPC ===

    /** {@code gasMembership_getRejection(txHash)} → último rechazo de esa TX o null. */
    Object handleGetRejection(final PluginRpcRequest request) {
        Object[] params = request.getParams();
        if (params == null || params.length < 1 || params[0] == null) {
            throw new PluginRpcEndpointException(invalidParams("se esperaba param[0]=txHash (0x + 64 hex)"));
        }
        Hash txHash;
        try {
            txHash = Hash.fromHexString(params[0].toString());
        } catch (RuntimeException e) {
            throw new PluginRpcEndpointException(invalidParams("txHash inválido: " + params[0]));
        }
        return eventBus.get(txHash).map(GasMembershipPlugin::toMap).orElse(null);
    }

    /** {@code gasMembership_listRejectionsBySender(sender, limit?)} → lista de rechazos del sender. */
    Object handleListRejectionsBySender(final PluginRpcRequest request) {
        Object[] params = request.getParams();
        if (params == null || params.length < 1 || params[0] == null) {
            throw new PluginRpcEndpointException(invalidParams("se esperaba param[0]=sender (0x + 40 hex)"));
        }
        Address sender;
        try {
            sender = Address.fromHexString(params[0].toString());
        } catch (RuntimeException e) {
            throw new PluginRpcEndpointException(invalidParams("sender inválido: " + params[0]));
        }
        int limit = 20;
        if (params.length >= 2 && params[1] != null) {
            try {
                limit = Integer.parseInt(params[1].toString());
            } catch (NumberFormatException ignored) {
                // se queda con el default
            }
        }
        return eventBus.listBySender(sender, limit).stream()
                .map(GasMembershipPlugin::toMap)
                .toList();
    }

    /**
     * {@code gasMembership_getMonthlyUsage(account)} → estado de cuota mensual de la cuenta (Fase 2):
     * {@code {periodId, period, used, blocked, blockedUntil}}. Devuelve null si Fase 2 no está activa.
     */
    Object handleGetMonthlyUsage(final PluginRpcRequest request) {
        if (monthlyTracker == null || blockedAddresses == null) {
            return null; // Fase 2 inactiva en este nodo
        }
        Object[] params = request.getParams();
        if (params == null || params.length < 1 || params[0] == null) {
            throw new PluginRpcEndpointException(invalidParams("se esperaba param[0]=account (0x + 40 hex)"));
        }
        Address account;
        try {
            account = Address.fromHexString(params[0].toString());
        } catch (RuntimeException e) {
            throw new PluginRpcEndpointException(invalidParams("account inválido: " + params[0]));
        }
        long now = System.currentTimeMillis();
        long periodId = monthlyTracker.currentPeriodId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", account.toHexString());
        m.put("periodId", periodId);
        m.put("period", PeriodClock.label(periodId));
        m.put("used", monthlyTracker.cumulative(account, (p, a) -> 0L));
        boolean isBlocked = blockedAddresses.isBlocked(account, now);
        m.put("blocked", isBlocked);
        m.put("blockedUntil", blockedAddresses.blockedUntil(account, now).orElse(null));
        return m;
    }

    private static Map<String, Object> toMap(final RejectionEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txHash", ev.txHash().toHexString());
        m.put("sender", ev.sender().toHexString());
        m.put("tier", ev.tier().name());
        m.put("reason", ev.reason());
        m.put("blockNumber", ev.blockNumber());
        m.put("txGasLimit", ev.txGasLimit());
        m.put("usedInBlock", ev.usedInBlock());
        m.put("quota", ev.quota());
        m.put("timestamp", ev.timestamp().toEpochMilli());
        m.put("source", ev.source().name());
        return m;
    }

    private static RpcMethodError invalidParams(final String message) {
        return new RpcMethodError() {
            @Override
            public int getCode() {
                return RpcMethodError.INVALID_PARAMS_ERROR_CODE;
            }

            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    @Override
    public void start() {
        LOG.info("{}: start()", NAME);
        // BesuEvents/BlockchainService recién están disponibles acá → completar el wiring de Fase 2.
        startMonthlyEnforcement();
    }

    @Override
    public void stop() {
        LOG.info("{}: stop()", NAME);
    }

    // === Visible para tests ===

    GasMembershipConfig config() {
        return config;
    }

    GasMembershipTransactionSelectorFactory factory() {
        return factory;
    }

    GasMembershipTransactionValidatorFactory validatorFactory() {
        return validatorFactory;
    }

    RejectionEventBus eventBus() {
        return eventBus;
    }
}
