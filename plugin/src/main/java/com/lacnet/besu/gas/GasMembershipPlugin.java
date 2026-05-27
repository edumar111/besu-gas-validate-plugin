package com.lacnet.besu.gas;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorFactory;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.validator.GasMembershipTransactionValidatorFactory;
import java.util.Optional;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.TransactionPoolValidatorService;
import org.hyperledger.besu.plugin.services.TransactionSelectionService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
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

        // 3. Construir el grafo de objetos del plugin. Cache, tracker y client se comparten
        //    entre selector y validator para evitar doble lookup al contrato.
        Address contractAddress = Address.fromHexString(config.getMembershipContractAddress());
        MembershipContractClient client = new MembershipContractClient(simulator, contractAddress);
        TierCache cache = new TierCache(config.getTierCacheTtlBlocks());
        BlockGasTracker tracker = new BlockGasTracker();
        this.factory = new GasMembershipTransactionSelectorFactory(config, client, cache, tracker);
        this.validatorFactory = new GasMembershipTransactionValidatorFactory(config, client, cache, simulator);

        // 4. Registrar.
        selection.registerPluginTransactionSelectorFactory(factory);
        validation.registerPluginTransactionValidatorFactory(validatorFactory);

        LOG.info("{}: registrado (contract={} ttlBlocks={} quotas: BASIC={} STANDARD={} PREMIUM={})",
                NAME,
                config.getMembershipContractAddress(),
                config.getTierCacheTtlBlocks(),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.BASIC),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.STANDARD),
                config.getQuotaPerBlock(com.lacnet.besu.gas.model.Tier.PREMIUM));
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
}
