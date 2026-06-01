package com.lacnet.besu.gas.validator;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.usage.MonthlyQuotaGuard;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidator;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidatorFactory;

/**
 * Factory para crear instancias de {@link GasMembershipTransactionValidator}.
 * Comparte la misma {@link TierCache}, {@link MembershipContractClient},
 * {@link GasMembershipConfig} y {@link RejectionEventBus} que el factory del
 * selector — un solo lookup al contrato por (sender, bloque) sirve a ambas
 * evaluaciones, y ambos escriben al mismo bus de rechazos.
 */
public class GasMembershipTransactionValidatorFactory implements PluginTransactionPoolValidatorFactory {

    private final GasMembershipConfig config;
    private final MembershipContractClient client;
    private final TierCache cache;
    private final TransactionSimulationService simulator;
    private final RejectionEventBus eventBus;
    /** Enforcement mensual (Fase 2). {@code null} → solo per-block (Fase 1). */
    private final MonthlyQuotaGuard monthlyGuard;

    public GasMembershipTransactionValidatorFactory(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final TransactionSimulationService simulator,
            final RejectionEventBus eventBus) {
        this(config, client, cache, simulator, eventBus, null);
    }

    public GasMembershipTransactionValidatorFactory(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final TransactionSimulationService simulator,
            final RejectionEventBus eventBus,
            final MonthlyQuotaGuard monthlyGuard) {
        this.config = config;
        this.client = client;
        this.cache = cache;
        this.simulator = simulator;
        this.eventBus = eventBus;
        this.monthlyGuard = monthlyGuard;
    }

    @Override
    public PluginTransactionPoolValidator createTransactionValidator() {
        return new GasMembershipTransactionValidator(config, client, cache, simulator, eventBus, monthlyGuard);
    }
}
