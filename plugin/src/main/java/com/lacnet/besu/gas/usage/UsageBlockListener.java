package com.lacnet.besu.gas.usage;

import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.model.Tier;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.AddedBlockContext;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.TransactionReceipt;
import org.hyperledger.besu.plugin.services.BesuEvents.BlockAddedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contabiliza el consumo de gas <b>mensual</b> por cuenta y aplica el bloqueo por exceso (Fase 2).
 *
 * <p>Se registra como {@link BlockAddedListener}, así que corre en <b>todos los nodos</b> al añadir
 * cada bloque (no solo en el proposer). Por cada bloque:
 * <ol>
 *   <li>Deriva el {@code periodId} (mes calendario UTC) del timestamp del header y hace
 *       {@code rollTo} en el tracker (resetea si cambió el mes).</li>
 *   <li>Atribuye el {@code gasUsed} real de cada TX a su sender. El gas por TX es el delta de
 *       {@code getCumulativeGasUsed()} entre receipts consecutivos (los receipts no exponen gas por
 *       TX directamente).</li>
 *   <li>Para cada sender tocado, no-WHITELISTED, cuyo acumulado mensual supere su cuota: lo agrega
 *       al {@link BlockedAddressRegistry} (TTL 5 min). El validator y el selector rechazarán sus TX
 *       siguientes — este listener <b>no</b> emite {@code RejectionEvent} (esos son por-TX).</li>
 *   <li>Cada {@code commitEveryBlocks} bloques dispara el {@link CommitTrigger} (commit on-chain en
 *       el nodo recorder; no-op en los demás).</li>
 * </ol>
 *
 * <p>El bloqueo usa wall-clock ({@link Clock}) porque es una penalización local; el período usa el
 * timestamp del bloque para ser consenso-consistente entre nodos.
 */
public final class UsageBlockListener implements BlockAddedListener {

    private static final Logger LOG = LoggerFactory.getLogger(UsageBlockListener.class);

    private final GasMembershipConfig config;
    private final MonthlyUsageTracker tracker;
    private final BlockedAddressRegistry blocked;
    private final TierResolver tierResolver;
    private final MonthlyUsageTracker.BaselineLoader baselineLoader;
    private final Clock clock;
    private final CommitTrigger commitTrigger;

    public UsageBlockListener(
            final GasMembershipConfig config,
            final MonthlyUsageTracker tracker,
            final BlockedAddressRegistry blocked,
            final TierResolver tierResolver,
            final MonthlyUsageTracker.BaselineLoader baselineLoader,
            final Clock clock,
            final CommitTrigger commitTrigger) {
        this.config = config;
        this.tracker = tracker;
        this.blocked = blocked;
        this.tierResolver = tierResolver;
        this.baselineLoader = baselineLoader;
        this.clock = clock;
        this.commitTrigger = commitTrigger;
    }

    @Override
    public void onBlockAdded(final AddedBlockContext context) {
        final BlockHeader header = context.getBlockHeader();
        final long blockNumber = header.getNumber();
        final long periodId = PeriodClock.periodId(header.getTimestamp());

        // 1. Rollover de período (mes nuevo → reset de contadores).
        tracker.rollTo(periodId);

        // 2. Atribuir gasUsed real por sender (delta de cumulative gas entre receipts).
        final List<? extends Transaction> txs = context.getBlockBody().getTransactions();
        final List<? extends TransactionReceipt> receipts = context.getTransactionReceipts();
        attributeUsage(txs, receipts, blockNumber);

        // 3. Disparar commit on-chain según cadencia (no-op fuera del nodo recorder).
        if (commitTrigger != null && config.getUsageCommitEveryBlocks() > 0
                && blockNumber % config.getUsageCommitEveryBlocks() == 0) {
            try {
                commitTrigger.onCommitDue(blockNumber);
            } catch (RuntimeException e) {
                LOG.warn("commit trigger falló en bloque {}", blockNumber, e);
            }
        }
    }

    private void attributeUsage(
            final List<? extends Transaction> txs,
            final List<? extends TransactionReceipt> receipts,
            final long blockNumber) {
        if (txs.isEmpty()) {
            return;
        }
        if (receipts.size() != txs.size()) {
            LOG.warn("bloque {}: #receipts ({}) != #txs ({}); se omite el accounting mensual",
                    blockNumber, receipts.size(), txs.size());
            return;
        }

        // Acumular gas por sender en este bloque antes de evaluar el bloqueo (un sender puede
        // aparecer en varias TX del mismo bloque → una sola evaluación de cuota).
        final Map<Address, Long> gasBySender = new HashMap<>();
        long prevCumulative = 0L;
        for (int i = 0; i < txs.size(); i++) {
            long cumulative = receipts.get(i).getCumulativeGasUsed();
            long gasUsed = cumulative - prevCumulative;
            prevCumulative = cumulative;
            if (gasUsed <= 0) {
                continue;
            }
            gasBySender.merge(txs.get(i).getSender(), gasUsed, Long::sum);
        }

        final long nowMillis = clock.millis();
        for (Map.Entry<Address, Long> en : gasBySender.entrySet()) {
            final Address sender = en.getKey();
            tracker.addUsage(sender, en.getValue(), baselineLoader);

            final Tier tier = tierResolver.tierOf(sender, blockNumber);
            if (tier == Tier.WHITELISTED) {
                continue; // bypass: relay/admin no tienen cuota mensual
            }
            final long monthlyQuota = config.getMonthlyQuota(tier);
            final long cumulative = tracker.cumulative(sender, baselineLoader);
            if (cumulative > monthlyQuota) {
                blocked.block(sender, nowMillis, config.getMonthlyBlockDurationMillis());
                LOG.info("cuenta {} excedió cuota mensual ({} > {}) tier {} en bloque {}; "
                                + "bloqueada por {}s",
                        sender, cumulative, monthlyQuota, tier, blockNumber,
                        config.getMonthlyBlockDurationSeconds());
            }
        }
    }
}
