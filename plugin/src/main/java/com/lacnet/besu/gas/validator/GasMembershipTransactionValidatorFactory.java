package com.lacnet.besu.gas.validator;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidator;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidatorFactory;

/**
 * Factory para crear instancias de {@link GasMembershipTransactionValidator}.
 * Comparte la misma {@link TierCache}, {@link MembershipContractClient} y
 * {@link GasMembershipConfig} que el factory del selector — un solo lookup
 * al contrato por (sender, bloque) sirve a ambas evaluaciones.
 */
public class GasMembershipTransactionValidatorFactory implements PluginTransactionPoolValidatorFactory {

    private final GasMembershipConfig config;
    private final MembershipContractClient client;
    private final TierCache cache;
    private final TransactionSimulationService simulator;

    public GasMembershipTransactionValidatorFactory(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final TransactionSimulationService simulator) {
        this.config = config;
        this.client = client;
        this.cache = cache;
        this.simulator = simulator;
    }

    @Override
    public PluginTransactionPoolValidator createTransactionValidator() {
        return new GasMembershipTransactionValidator(config, client, cache, simulator);
    }
}
