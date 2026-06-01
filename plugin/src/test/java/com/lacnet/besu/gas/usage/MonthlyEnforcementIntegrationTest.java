package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelector;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import com.lacnet.besu.gas.validator.GasMembershipTransactionValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.txselection.TransactionEvaluationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifica el enforcement mensual (Fase 2) cableado en el selector y el validator vía
 * {@link MonthlyQuotaGuard} compartido.
 */
@ExtendWith(MockitoExtension.class)
class MonthlyEnforcementIntegrationTest {

    private static final Address BASIC_ACC = Address.fromHexString("0x00000000000000000000000000000000000000b1");
    private static final Address WL_ACC = Address.fromHexString("0x00000000000000000000000000000000000000f1");
    private static final long MONTHLY_BASIC = 1_000_000L;
    private static final long PER_BLOCK_BASIC = 500_000L;
    private static final long PERIOD = 2026L * 12 + 4;

    private GasMembershipConfig config;
    private MonthlyUsageTracker tracker;
    private BlockedAddressRegistry blocked;
    private MonthlyQuotaGuard guard;
    private RejectionEventBus bus;
    private MonthlyUsageTracker.BaselineLoader zeroBaseline;
    private long nowMillis = 1_000_000L;

    @Mock private MembershipContractClient client;
    @Mock private TierCache cache;
    @Mock private BlockGasTracker blockTracker;
    @Mock private TransactionSimulationService simulator;
    @Mock private ProcessableBlockHeader pendingHeader;

    @BeforeEach
    void setUp() {
        Map<String, String> props = new HashMap<>();
        props.put(GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT,
                "0x1234567890123456789012345678901234567890");
        props.put(GasMembershipConfig.PROP_GAS_MONTHLY_BASIC, Long.toString(MONTHLY_BASIC));
        props.put(GasMembershipConfig.PROP_GAS_BASIC, Long.toString(PER_BLOCK_BASIC));
        config = GasMembershipConfig.fromProperties(props::get);

        tracker = new MonthlyUsageTracker(PERIOD);
        blocked = new BlockedAddressRegistry();
        bus = new RejectionEventBus();
        zeroBaseline = (p, a) -> 0L;
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis); }
            @Override public long millis() { return nowMillis; }
        };
        guard = new MonthlyQuotaGuard(config, tracker, blocked, zeroBaseline, clock);
    }

    // ---------- Selector ----------

    private TransactionSelectionResult selectorEval(Address sender, Tier tier, long txGasLimit) {
        GasMembershipTransactionSelector selector = new GasMembershipTransactionSelector(
                config, client, cache, blockTracker, bus, guard);
        TransactionEvaluationContext ctx = org.mockito.Mockito.mock(TransactionEvaluationContext.class);
        var pending = org.mockito.Mockito.mock(
                org.hyperledger.besu.datatypes.PendingTransaction.class);
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        lenient().when(ctx.getPendingBlockHeader()).thenReturn(pendingHeader);
        lenient().when(pendingHeader.getNumber()).thenReturn(100L);
        lenient().when(pendingHeader.getGasLimit()).thenReturn(350_000_000L);
        lenient().when(ctx.getPendingTransaction()).thenReturn(pending);
        lenient().when(pending.getTransaction()).thenReturn(tx);
        lenient().when(tx.getSender()).thenReturn(sender);
        lenient().when(tx.getGasLimit()).thenReturn(txGasLimit);
        lenient().when(tx.getHash()).thenReturn(Hash.ZERO);
        lenient().when(cache.getOrLoad(org.mockito.ArgumentMatchers.eq(sender),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(tier);
        lenient().when(blockTracker.getUsed(sender)).thenReturn(0L);
        lenient().when(blockTracker.totalUsed()).thenReturn(0L);
        return selector.evaluateTransactionPreProcessing(ctx);
    }

    @Test
    void selectorRejectsWhenMonthlyExceeded() {
        TransactionSelectionResult r = selectorEval(BASIC_ACC, Tier.BASIC, 1_200_000L);
        assertFalse(r.selected());
        assertTrue(blocked.isBlocked(BASIC_ACC, nowMillis), "debe bloquear la cuenta");
        Optional<RejectionEvent> ev = bus.get(Hash.ZERO);
        assertTrue(ev.isPresent());
        assertEquals(GasMembershipTransactionSelector.REASON_MONTHLY_QUOTA_EXCEEDED, ev.get().reason());
    }

    @Test
    void selectorRejectsAlreadyBlockedAccount() {
        blocked.block(BASIC_ACC, nowMillis, 300_000L);
        TransactionSelectionResult r = selectorEval(BASIC_ACC, Tier.BASIC, 100_000L);
        assertFalse(r.selected());
        Optional<RejectionEvent> ev = bus.get(Hash.ZERO);
        assertTrue(ev.isPresent());
        assertEquals(GasMembershipTransactionSelector.REASON_ACCOUNT_BLOCKED, ev.get().reason());
    }

    @Test
    void selectorAllowsWithinMonthlyQuota() {
        // 100k ≤ per-block 500k y ≤ mensual 1M → SELECTED
        TransactionSelectionResult r = selectorEval(BASIC_ACC, Tier.BASIC, 100_000L);
        assertTrue(r.selected());
        assertFalse(blocked.isBlocked(BASIC_ACC, nowMillis));
    }

    @Test
    void selectorWhitelistedBypassesMonthly() {
        TransactionSelectionResult r = selectorEval(WL_ACC, Tier.WHITELISTED, 50_000_000L);
        assertTrue(r.selected());
        assertFalse(blocked.isBlocked(WL_ACC, nowMillis));
    }

    // ---------- Validator ----------

    private Optional<String> validatorEval(Address sender, Tier tier, long txGasLimit) {
        GasMembershipTransactionValidator validator = new GasMembershipTransactionValidator(
                config, client, cache, simulator, bus, guard);
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        lenient().when(tx.getSender()).thenReturn(sender);
        lenient().when(tx.getGasLimit()).thenReturn(txGasLimit);
        lenient().when(tx.getHash()).thenReturn(Hash.ZERO);
        lenient().when(simulator.simulatePendingBlockHeader()).thenReturn(pendingHeader);
        lenient().when(pendingHeader.getNumber()).thenReturn(100L);
        lenient().when(cache.getOrLoad(org.mockito.ArgumentMatchers.eq(sender),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(tier);
        return validator.validateTransaction(tx, true, false);
    }

    @Test
    void validatorRejectsWhenMonthlyExceeded() {
        Optional<String> r = validatorEval(BASIC_ACC, Tier.BASIC, 1_200_000L);
        assertTrue(r.isPresent());
        assertEquals(GasMembershipTransactionSelector.REASON_MONTHLY_QUOTA_EXCEEDED, r.get());
        assertTrue(blocked.isBlocked(BASIC_ACC, nowMillis));
    }

    @Test
    void validatorRejectsAlreadyBlockedAccount() {
        blocked.block(BASIC_ACC, nowMillis, 300_000L);
        Optional<String> r = validatorEval(BASIC_ACC, Tier.BASIC, 100_000L);
        assertTrue(r.isPresent());
        assertEquals(GasMembershipTransactionSelector.REASON_ACCOUNT_BLOCKED, r.get());
    }

    @Test
    void validatorAllowsWithinMonthlyQuota() {
        Optional<String> r = validatorEval(BASIC_ACC, Tier.BASIC, 100_000L);
        assertTrue(r.isEmpty());
    }

    @Test
    void validatorWhitelistedBypassesMonthly() {
        Optional<String> r = validatorEval(WL_ACC, Tier.WHITELISTED, 50_000_000L);
        assertTrue(r.isEmpty());
    }

    // ---------- Guard unitario ----------

    @Test
    void guardAccumulatesAcrossCalls() {
        // dos TX de 600k: la primera OK (600k ≤ 1M), la segunda EXCEEDED (1.2M > 1M)
        tracker.addUsage(BASIC_ACC, 600_000L, zeroBaseline);
        assertEquals(MonthlyQuotaGuard.Decision.OK, guard.check(BASIC_ACC, Tier.BASIC, 300_000L));
        tracker.addUsage(BASIC_ACC, 600_000L, zeroBaseline);
        assertEquals(MonthlyQuotaGuard.Decision.EXCEEDED, guard.check(BASIC_ACC, Tier.BASIC, 1L));
    }

    @Test
    void guardListIsConsistentForMultipleTiers() {
        assertEquals(List.of(MonthlyQuotaGuard.Decision.OK),
                List.of(guard.check(WL_ACC, Tier.WHITELISTED, Long.MAX_VALUE / 2)));
    }
}
