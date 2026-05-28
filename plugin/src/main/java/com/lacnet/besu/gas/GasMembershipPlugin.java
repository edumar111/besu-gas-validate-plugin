package com.lacnet.besu.gas;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorFactory;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.validator.GasMembershipTransactionValidatorFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
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
 *       — decide qué TXs entran a cada bloque (con contexto de gas usado per-block).</li>
 *   <li>{@link GasMembershipTransactionValidatorFactory} contra
 *       {@link TransactionPoolValidatorService} — rechaza al admitir al txpool las TXs que
 *       el plugin sabe que <em>nunca</em> van a poder ser incluidas (Fase 1.5).</li>
 * </ul>
 *
 * <p>Cargado por Besu vía Java SPI ({@code META-INF/services/org.hyperledger.besu.plugin.BesuPlugin}).
 * El constructor sin args es obligatorio para el {@link java.util.ServiceLoader}.
 */
public class GasMembershipPlugin implements BesuPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(GasMembershipPlugin.class);
    private static final String NAME = "GasMembershipPlugin";

    private final Function<String, String> propertyResolver;

    private GasMembershipConfig config;
    private GasMembershipTransactionSelectorFactory factory;
    private GasMembershipTransactionValidatorFactory validatorFactory;
    private RejectionEventBus eventBus;

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

        // 3. Construir el grafo de objetos del plugin. Cache, tracker, client y eventBus se
        //    comparten entre selector y validator (un solo lookup al contrato, un solo bus).
        Address contractAddress = Address.fromHexString(config.getMembershipContractAddress());
        MembershipContractClient client = new MembershipContractClient(simulator, contractAddress);
        TierCache cache = new TierCache(config.getTierCacheTtlBlocks());
        BlockGasTracker tracker = new BlockGasTracker();
        this.eventBus = new RejectionEventBus();
        this.factory = new GasMembershipTransactionSelectorFactory(config, client, cache, tracker, eventBus);
        this.validatorFactory = new GasMembershipTransactionValidatorFactory(config, client, cache, simulator, eventBus);

        // 4. Registrar selector, validator y los métodos JSON-RPC custom.
        selection.registerPluginTransactionSelectorFactory(factory);
        validation.registerPluginTransactionValidatorFactory(validatorFactory);
        rpc.registerRPCEndpoint("gasMembership", "getRejection", this::handleGetRejection);
        rpc.registerRPCEndpoint("gasMembership", "listRejectionsBySender", this::handleListRejectionsBySender);

        LOG.info("{}: registrado (contract={} ttlBlocks={} quotas: BASIC={} STANDARD={} PREMIUM={})",
                NAME,
                config.getMembershipContractAddress(),
                config.getTierCacheTtlBlocks(),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.BASIC),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.STANDARD),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.PREMIUM));
    }

    // === Handlers JSON-RPC (Fase 1.6) ===

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
