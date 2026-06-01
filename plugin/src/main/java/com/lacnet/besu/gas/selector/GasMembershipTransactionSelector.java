package com.lacnet.besu.gas.selector;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.usage.MonthlyQuotaGuard;
import java.time.Instant;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.txselection.PluginTransactionSelector;
import org.hyperledger.besu.plugin.services.txselection.TransactionEvaluationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decide si una TX entra al bloque actual según la cuota de gas por bloque del tier del sender.
 *
 * <p>Flujo (Fase 1, ver docs/§7):
 * <ol>
 *   <li>Detecta cambio de bloque → resetea contadores.</li>
 *   <li>Lookup del tier vía cache (con load on miss).</li>
 *   <li>{@code WHITELISTED} → SELECTED.</li>
 *   <li>{@code NONE} → INVALID ({@code "sender has no active membership"}).</li>
 *   <li>{@code gasUsedSoFar + tx.gasLimit ≤ tierQuota} → SELECTED.</li>
 *   <li>{@code PREMIUM} y la TX cabe en el sobrante del bloque
 *       ({@code blockGasLimit - totalUsed}) → SELECTED.</li>
 *   <li>Tier no PREMIUM y {@code tx.gasLimit > tierQuota} (la TX por sí sola excede la cuota
 *       del tier — no va a caber en ningún bloque futuro) → INVALID permanente
 *       ({@code "tx gasLimit exceeds tier quota"}). La TX se descarta del txpool.</li>
 *   <li>Sino → INVALID_TRANSIENT ({@code "excedió límite de gas en el bloque"}). Sin penalización
 *       persistente: la TX queda en el txpool y puede reintentarse en el bloque siguiente.</li>
 * </ol>
 *
 * <p>En post-processing acumula el {@code gasUsed} real en el tracker. La decisión de pre-proc
 * usa {@code tx.gasLimit} (conservadora); la contabilidad de post-proc usa el real (exacta).
 *
 * <p><b>Importante</b>: las dependencias ({@link BlockGasTracker}, {@link TierCache}) son
 * <em>compartidas entre instancias</em> de selector creadas por el mismo factory. Múltiples
 * selectors viviendo en paralelo durante una ronda de selección comparten el estado del bloque.
 */
public class GasMembershipTransactionSelector implements PluginTransactionSelector {

    private static final Logger LOG = LoggerFactory.getLogger(GasMembershipTransactionSelector.class);

    public static final String REASON_NO_MEMBERSHIP = "sender has no active membership";
    public static final String REASON_BLOCK_QUOTA_EXCEEDED = "excedió límite de gas en el bloque";
    public static final String REASON_TX_EXCEEDS_TIER_QUOTA = "tx gasLimit exceeds tier quota";
    public static final String REASON_ACCOUNT_BLOCKED =
            "account temporarily blocked: monthly gas quota exceeded";
    public static final String REASON_MONTHLY_QUOTA_EXCEEDED = "monthly gas quota exceeded";

    private final GasMembershipConfig config;
    private final MembershipContractClient client;
    private final TierCache cache;
    private final BlockGasTracker tracker;
    private final RejectionEventBus eventBus;
    /** Enforcement mensual (Fase 2). {@code null} → solo enforcement per-block (Fase 1). */
    private final MonthlyQuotaGuard monthlyGuard;

    public GasMembershipTransactionSelector(
            final GasMembershipConfig config,
            final MembershipContractClient client,
            final TierCache cache,
            final BlockGasTracker tracker,
            final RejectionEventBus eventBus) {
        this(config, client, cache, tracker, eventBus, null);
    }

    public GasMembershipTransactionSelector(
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
    public TransactionSelectionResult evaluateTransactionPreProcessing(
            final TransactionEvaluationContext context) {
        ProcessableBlockHeader header = context.getPendingBlockHeader();
        long blockNumber = header.getNumber();
        Transaction tx = context.getPendingTransaction().getTransaction();
        Address sender = tx.getSender();
        long txGasLimit = tx.getGasLimit();

        tracker.onBlockChange(blockNumber);

        Tier tier = cache.getOrLoad(sender, blockNumber, client::getTier);

        if (tier == Tier.WHITELISTED) {
            return TransactionSelectionResult.SELECTED;
        }

        if (tier == Tier.NONE) {
            LOG.debug("Rechazo sender={} tier=NONE", sender);
            emit(tx.getHash(), sender, tier, REASON_NO_MEMBERSHIP, blockNumber, txGasLimit, 0L, 0L);
            return TransactionSelectionResult.invalid(REASON_NO_MEMBERSHIP);
        }

        // Enforcement mensual (Fase 2) antes del per-block. Transient: el bloqueo dura ~5 min y la
        // cuota mensual se resetea el mes próximo, así que la TX puede reintentarse — no la
        // descartamos del pool.
        if (monthlyGuard != null) {
            MonthlyQuotaGuard.Decision decision = monthlyGuard.check(sender, tier, txGasLimit);
            if (decision == MonthlyQuotaGuard.Decision.BLOCKED) {
                LOG.debug("Rechazo sender={} tier={}: cuenta bloqueada (cuota mensual)", sender, tier);
                emit(tx.getHash(), sender, tier, REASON_ACCOUNT_BLOCKED, blockNumber, txGasLimit,
                        tracker.getUsed(sender), monthlyGuard.monthlyQuotaOf(tier));
                return TransactionSelectionResult.invalidTransient(REASON_ACCOUNT_BLOCKED);
            }
            if (decision == MonthlyQuotaGuard.Decision.EXCEEDED) {
                LOG.debug("Rechazo sender={} tier={}: excede cuota mensual", sender, tier);
                emit(tx.getHash(), sender, tier, REASON_MONTHLY_QUOTA_EXCEEDED, blockNumber, txGasLimit,
                        tracker.getUsed(sender), monthlyGuard.monthlyQuotaOf(tier));
                return TransactionSelectionResult.invalidTransient(REASON_MONTHLY_QUOTA_EXCEEDED);
            }
        }

        long quota = config.getQuotaPerBlock(tier);
        long used = tracker.getUsed(sender);
        long projected = used + txGasLimit;

        if (projected <= quota) {
            return TransactionSelectionResult.SELECTED;
        }

        // Excede su cuota de tier. PREMIUM puede consumir sobrante del bloque; el resto no.
        if (tier == Tier.PREMIUM) {
            long blockGasLimit = header.getGasLimit();
            long leftover = blockGasLimit - tracker.totalUsed();
            if (txGasLimit <= leftover) {
                LOG.debug("PREMIUM sender={} usando sobrante: txGasLimit={} leftover={}",
                        sender, txGasLimit, leftover);
                return TransactionSelectionResult.SELECTED;
            }
            LOG.debug("PREMIUM sender={} excede tanto cuota como sobrante: "
                    + "projected={} quota={} txGasLimit={} leftover={}",
                    sender, projected, quota, txGasLimit, leftover);
            emit(tx.getHash(), sender, tier, REASON_BLOCK_QUOTA_EXCEEDED, blockNumber, txGasLimit, used, quota);
            return TransactionSelectionResult.invalidTransient(REASON_BLOCK_QUOTA_EXCEEDED);
        }

        // BASIC/STANDARD: si la TX por sí sola ya excede la cuota del tier, no va a caber en
        // ningún bloque futuro (txGasLimit es fijo). La descartamos del txpool permanentemente.
        if (txGasLimit > quota) {
            LOG.debug("Rechazo permanente sender={} tier={} txGasLimit={} quota={}: TX no cabe en ningún bloque",
                    sender, tier, txGasLimit, quota);
            emit(tx.getHash(), sender, tier, REASON_TX_EXCEEDS_TIER_QUOTA, blockNumber, txGasLimit, used, quota);
            return TransactionSelectionResult.invalid(REASON_TX_EXCEEDS_TIER_QUOTA);
        }

        // txGasLimit ≤ quota pero used + txGasLimit > quota → puede caber cuando se resetee el contador.
        LOG.debug("Rechazo sender={} tier={} projected={} quota={}",
                sender, tier, projected, quota);
        emit(tx.getHash(), sender, tier, REASON_BLOCK_QUOTA_EXCEEDED, blockNumber, txGasLimit, used, quota);
        return TransactionSelectionResult.invalidTransient(REASON_BLOCK_QUOTA_EXCEEDED);
    }

    @Override
    public TransactionSelectionResult evaluateTransactionPostProcessing(
            final TransactionEvaluationContext context,
            final TransactionProcessingResult processingResult) {
        Address sender = context.getPendingTransaction().getTransaction().getSender();
        long gasUsed = processingResult.getEstimateGasUsedByTransaction();
        tracker.add(sender, gasUsed);
        return TransactionSelectionResult.SELECTED;
    }

    private void emit(
            final Hash txHash,
            final Address sender,
            final Tier tier,
            final String reason,
            final long blockNumber,
            final long txGasLimit,
            final long used,
            final long quota) {
        eventBus.emit(new RejectionEvent(
                txHash, sender, tier, reason, blockNumber, txGasLimit, used, quota,
                Instant.now(), RejectionEvent.Source.SELECTOR));
    }
}
