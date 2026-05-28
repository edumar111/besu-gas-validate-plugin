package com.lacnet.besu.gas.validator;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelector;
import java.time.Instant;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Valida transacciones al momento de admitirlas al txpool. Rechaza upfront las
 * que el plugin sabe que <em>nunca</em> podrían ser incluidas en ningún bloque,
 * permitiendo que {@code eth_sendRawTransaction} devuelva error directo en vez
 * de aceptar la TX y que sea el selector quien la descarte después.
 *
 * <p>Reglas:
 * <ul>
 *   <li>{@code WHITELISTED} → válida.</li>
 *   <li>{@code PREMIUM} → válida. El selector decide con el sobrante del bloque
 *       — el validator no tiene esa info.</li>
 *   <li>{@code NONE} → inválida ({@code REASON_NO_MEMBERSHIP}). Igual semántica
 *       que el selector.</li>
 *   <li>{@code BASIC/STANDARD} y {@code txGasLimit > tierQuota} → inválida
 *       ({@code REASON_TX_EXCEEDS_TIER_QUOTA}). La TX por sí sola excede la
 *       cuota; no cabe en ningún bloque futuro.</li>
 *   <li>{@code BASIC/STANDARD} y {@code txGasLimit ≤ tierQuota} → válida. Podría
 *       no entrar en ESTE bloque por accounting per-block, pero eso lo decide el
 *       selector (puede ser {@code invalidTransient}).</li>
 * </ul>
 *
 * <p>El validator <strong>no</strong> ve el {@link ProcessableBlockHeader} ni el
 * gas usado per-block; eso es responsabilidad del selector.
 *
 * @see GasMembershipTransactionSelector
 */
public class GasMembershipTransactionValidator implements PluginTransactionPoolValidator {

    private static final Logger LOG = LoggerFactory.getLogger(GasMembershipTransactionValidator.class);

    private final GasMembershipConfig config;
    private final MembershipContractClient client;
    private final TierCache cache;
    private final TransactionSimulationService simulator;
    private final RejectionEventBus eventBus;

    public GasMembershipTransactionValidator(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final TransactionSimulationService simulator,
            final RejectionEventBus eventBus) {
        this.config = config;
        this.client = client;
        this.cache = cache;
        this.simulator = simulator;
        this.eventBus = eventBus;
    }

    @Override
    public Optional<String> validateTransaction(
            final Transaction transaction,
            final boolean isLocal,
            final boolean hasPriority) {
        Address sender = transaction.getSender();
        long txGasLimit = transaction.getGasLimit();

        Tier tier = lookupTier(sender);

        if (tier == Tier.WHITELISTED || tier == Tier.PREMIUM) {
            return Optional.empty();
        }

        if (tier == Tier.NONE) {
            LOG.debug("Validator: rechazo sender={} tier=NONE", sender);
            emit(transaction, sender, tier, GasMembershipTransactionSelector.REASON_NO_MEMBERSHIP, txGasLimit, 0L);
            return Optional.of(GasMembershipTransactionSelector.REASON_NO_MEMBERSHIP);
        }

        long quota = config.getQuotaPerBlock(tier);
        if (txGasLimit > quota) {
            LOG.debug("Validator: rechazo permanente sender={} tier={} txGasLimit={} quota={}",
                    sender, tier, txGasLimit, quota);
            emit(transaction, sender, tier, GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA, txGasLimit, quota);
            return Optional.of(GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA);
        }

        // txGasLimit ≤ quota → en principio puede caber; el selector decide.
        return Optional.empty();
    }

    private void emit(
            final Transaction tx,
            final Address sender,
            final Tier tier,
            final String reason,
            final long txGasLimit,
            final long quota) {
        // El validator no tiene block context: blockNumber y usedInBlock van en 0.
        eventBus.emit(new RejectionEvent(
                tx.getHash(), sender, tier, reason, 0L, txGasLimit, 0L, quota,
                Instant.now(), RejectionEvent.Source.VALIDATOR));
    }

    /**
     * Resuelve el tier del sender usando la {@link TierCache} compartida con el selector.
     * El block number lo obtenemos del header pendiente del simulador (mismo que usa
     * el cliente internamente para el {@code eth_call}). Si falla, hacemos lookup directo.
     */
    private Tier lookupTier(final Address sender) {
        try {
            long blockNumber = simulator.simulatePendingBlockHeader().getNumber();
            return cache.getOrLoad(sender, blockNumber, client::getTier);
        } catch (RuntimeException e) {
            LOG.debug("Validator: no pude resolver pending block ({}); lookup directo para {}",
                    e.getMessage(), sender);
            return client.getTier(sender);
        }
    }
}
