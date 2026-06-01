package com.lacnet.besu.gas.selector;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.usage.MonthlyQuotaGuard;
import org.hyperledger.besu.plugin.services.txselection.PluginTransactionSelector;
import org.hyperledger.besu.plugin.services.txselection.PluginTransactionSelectorFactory;
import org.hyperledger.besu.plugin.services.txselection.SelectorsStateManager;

/**
 * Crea instancias de {@link GasMembershipTransactionSelector} compartiendo
 * {@link TierCache}, {@link BlockGasTracker} y {@link RejectionEventBus} entre todas las invocaciones.
 *
 * <p>Besu puede llamar a {@link #create} múltiples veces (una por ronda de selección, por proposer
 * paralelo, etc.). El estado de gas por bloque debe ser único — por eso lo creamos una vez en el
 * factory y lo inyectamos.
 */
public class GasMembershipTransactionSelectorFactory implements PluginTransactionSelectorFactory {

    private final GasMembershipConfig config;
    private final MembershipContractClient client;
    private final TierCache cache;
    private final BlockGasTracker tracker;
    private final RejectionEventBus eventBus;
    /** Enforcement mensual (Fase 2). {@code null} → solo per-block (Fase 1). */
    private final MonthlyQuotaGuard monthlyGuard;

    public GasMembershipTransactionSelectorFactory(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final BlockGasTracker tracker,
            final RejectionEventBus eventBus) {
        this(config, client, cache, tracker, eventBus, null);
    }

    public GasMembershipTransactionSelectorFactory(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final BlockGasTracker tracker,
            final RejectionEventBus eventBus,
            final MonthlyQuotaGuard monthlyGuard) {
        this.config = config;
        this.client = client;
        this.cache = cache;
        this.tracker = tracker;
        this.eventBus = eventBus;
        this.monthlyGuard = monthlyGuard;
    }

    @Override
    public PluginTransactionSelector create(final SelectorsStateManager stateManager) {
        return new GasMembershipTransactionSelector(config, client, cache, tracker, eventBus, monthlyGuard);
    }
}
