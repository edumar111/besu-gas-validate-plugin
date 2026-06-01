package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.model.Tier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.AddedBlockContext;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.TransactionReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageBlockListenerTest {

    private static final Address BASIC_ACC = Address.fromHexString("0x00000000000000000000000000000000000000b1");
    private static final Address WL_ACC = Address.fromHexString("0x00000000000000000000000000000000000000f1");

    // Cuota mensual BASIC chica para forzar el cruce con pocas TX.
    private static final long MONTHLY_BASIC = 1_000_000L;
    private static final long TS_MAY_2026 =
            ZonedDateTime.of(2026, 5, 15, 12, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
    private static final long PERIOD_MAY = PeriodClock.periodId(TS_MAY_2026);

    private GasMembershipConfig config;
    private MonthlyUsageTracker tracker;
    private BlockedAddressRegistry blocked;
    private MonthlyUsageTracker.BaselineLoader zeroBaseline;

    @Mock private AddedBlockContext ctx;
    @Mock private BlockHeader header;
    @Mock private BlockBody body;

    private final Map<Address, Tier> tiers = new HashMap<>();
    private final AtomicLong fixedNowMillis = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        Map<String, String> props = new HashMap<>();
        props.put(GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT,
                "0x1234567890123456789012345678901234567890");
        props.put(GasMembershipConfig.PROP_GAS_MONTHLY_BASIC, Long.toString(MONTHLY_BASIC));
        props.put(GasMembershipConfig.PROP_USAGE_COMMIT_EVERY_BLOCKS, "10");
        config = GasMembershipConfig.fromProperties(props::get);

        tracker = new MonthlyUsageTracker(PERIOD_MAY);
        blocked = new BlockedAddressRegistry();
        zeroBaseline = (p, a) -> 0L;
        tiers.clear();
        tiers.put(BASIC_ACC, Tier.BASIC);
        tiers.put(WL_ACC, Tier.WHITELISTED);
    }

    private UsageBlockListener newListener(CommitTrigger trigger) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(fixedNowMillis.get()); }
            @Override public long millis() { return fixedNowMillis.get(); }
        };
        TierResolver resolver = (acc, blk) -> tiers.getOrDefault(acc, Tier.NONE);
        return new UsageBlockListener(config, tracker, blocked, resolver, zeroBaseline, clock, trigger);
    }

    /** Cablea ctx/header/body para un bloque con número y timestamp dados y las TX/receipts dadas. */
    private void wireBlock(long blockNumber, long timestamp,
                           List<Address> senders, List<Long> gasPerTx) {
        when(ctx.getBlockHeader()).thenReturn(header);
        lenient().when(ctx.getBlockBody()).thenReturn(body);
        when(header.getNumber()).thenReturn(blockNumber);
        when(header.getTimestamp()).thenReturn(timestamp);

        List<Transaction> txs = new ArrayList<>();
        List<TransactionReceipt> receipts = new ArrayList<>();
        long cumulative = 0L;
        for (int i = 0; i < senders.size(); i++) {
            Transaction tx = org.mockito.Mockito.mock(Transaction.class);
            lenient().when(tx.getSender()).thenReturn(senders.get(i));
            txs.add(tx);
            cumulative += gasPerTx.get(i);
            TransactionReceipt r = org.mockito.Mockito.mock(TransactionReceipt.class);
            lenient().when(r.getCumulativeGasUsed()).thenReturn(cumulative);
            receipts.add(r);
        }
        lenient().when(body.getTransactions()).thenAnswer(inv -> txs);
        lenient().when(ctx.getTransactionReceipts()).thenAnswer(inv -> receipts);
    }

    @Test
    void attributesGasUsedPerSender() {
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC), List.of(300_000L));
        newListener(null).onBlockAdded(ctx);
        assertEquals(300_000L, tracker.cumulative(BASIC_ACC, zeroBaseline));
    }

    @Test
    void gasIsDeltaOfCumulativeBetweenReceipts() {
        // dos TX del mismo sender: 200k y 500k → cumulative 200k, 700k → debe atribuir 700k
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC, BASIC_ACC), List.of(200_000L, 500_000L));
        newListener(null).onBlockAdded(ctx);
        assertEquals(700_000L, tracker.cumulative(BASIC_ACC, zeroBaseline));
    }

    @Test
    void doesNotBlockBelowMonthlyQuota() {
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC), List.of(500_000L));
        newListener(null).onBlockAdded(ctx);
        assertFalse(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get()));
    }

    @Test
    void blocksWhenMonthlyQuotaExceeded() {
        // 1.2M > cuota 1M → bloquear
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC), List.of(1_200_000L));
        newListener(null).onBlockAdded(ctx);
        assertTrue(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get()));
        // y el bloqueo dura ~5 min (300s)
        assertTrue(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get() + 299_000L));
        assertFalse(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get() + 300_000L));
    }

    @Test
    void accumulatesAcrossBlocksThenBlocks() {
        UsageBlockListener listener = newListener(null);
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC), List.of(600_000L));
        listener.onBlockAdded(ctx);
        assertFalse(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get()));
        wireBlock(101, TS_MAY_2026, List.of(BASIC_ACC), List.of(600_000L)); // total 1.2M > 1M
        listener.onBlockAdded(ctx);
        assertTrue(blocked.isBlocked(BASIC_ACC, fixedNowMillis.get()));
    }

    @Test
    void whitelistedIsNeverBlocked() {
        wireBlock(100, TS_MAY_2026, List.of(WL_ACC), List.of(50_000_000L));
        newListener(null).onBlockAdded(ctx);
        assertFalse(blocked.isBlocked(WL_ACC, fixedNowMillis.get()));
        // pero su uso igual se contabiliza
        assertEquals(50_000_000L, tracker.cumulative(WL_ACC, zeroBaseline));
    }

    @Test
    void rollsOverPeriodOnMonthChange() {
        UsageBlockListener listener = newListener(null);
        wireBlock(100, TS_MAY_2026, List.of(BASIC_ACC), List.of(600_000L));
        listener.onBlockAdded(ctx);
        assertEquals(600_000L, tracker.cumulative(BASIC_ACC, zeroBaseline));

        long tsJune = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
        wireBlock(101, tsJune, List.of(BASIC_ACC), List.of(100_000L));
        listener.onBlockAdded(ctx);
        assertEquals(PERIOD_MAY + 1, tracker.currentPeriodId());
        // mes nuevo: el contador arranca de cero + lo de este bloque
        assertEquals(100_000L, tracker.cumulative(BASIC_ACC, zeroBaseline));
    }

    @Test
    void firesCommitTriggerOnCadence() {
        List<Long> fired = new ArrayList<>();
        CommitTrigger trigger = fired::add;
        UsageBlockListener listener = newListener(trigger);

        wireBlock(9, TS_MAY_2026, List.of(BASIC_ACC), List.of(1L));
        listener.onBlockAdded(ctx);
        wireBlock(10, TS_MAY_2026, List.of(BASIC_ACC), List.of(1L)); // 10 % 10 == 0
        listener.onBlockAdded(ctx);
        wireBlock(20, TS_MAY_2026, List.of(BASIC_ACC), List.of(1L));
        listener.onBlockAdded(ctx);

        assertEquals(List.of(10L, 20L), fired);
    }

    @Test
    void emptyBlockIsNoOp() {
        wireBlock(100, TS_MAY_2026, List.of(), List.of());
        newListener(null).onBlockAdded(ctx);
        assertEquals(PERIOD_MAY, tracker.currentPeriodId());
    }

    @Test
    void mismatchedReceiptsSkipsAccounting() {
        when(ctx.getBlockHeader()).thenReturn(header);
        when(header.getNumber()).thenReturn(100L);
        when(header.getTimestamp()).thenReturn(TS_MAY_2026);
        when(ctx.getBlockBody()).thenReturn(body);
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        List<Transaction> txs = List.of(tx);
        when(body.getTransactions()).thenAnswer(inv -> txs);
        when(ctx.getTransactionReceipts()).thenAnswer(inv -> new ArrayList<TransactionReceipt>());
        newListener(null).onBlockAdded(ctx);
        assertEquals(0L, tracker.cumulative(BASIC_ACC, zeroBaseline));
    }
}
