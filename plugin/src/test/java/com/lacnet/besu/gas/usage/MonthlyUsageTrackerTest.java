package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class MonthlyUsageTrackerTest {

    private static final long PERIOD = 2026L * 12 + 4;
    private static final Address ACC = Address.fromHexString("0x00000000000000000000000000000000000000b0");
    private static final Address ACC2 = Address.fromHexString("0x00000000000000000000000000000000000000c1");

    /** Loader que devuelve siempre 0 (cuenta nueva, sin uso on-chain). */
    private static final MonthlyUsageTracker.BaselineLoader ZERO = (p, a) -> 0L;

    @Test
    void startsAtZeroForNewAccount() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        assertEquals(0L, t.cumulative(ACC, ZERO));
        assertEquals(PERIOD, t.currentPeriodId());
    }

    @Test
    void addUsageAccumulatesIntoPending() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 1000L, ZERO);
        t.addUsage(ACC, 500L, ZERO);
        assertEquals(1500L, t.cumulative(ACC, ZERO));
    }

    @Test
    void cumulativeIsBaselinePlusPending() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        // baseline rehidratado desde la cadena = 10_000
        MonthlyUsageTracker.BaselineLoader loader = (p, a) -> 10_000L;
        t.addUsage(ACC, 250L, loader);
        assertEquals(10_250L, t.cumulative(ACC, loader));
    }

    @Test
    void baselineLoadedOncePerAccount() {
        AtomicInteger calls = new AtomicInteger();
        MonthlyUsageTracker.BaselineLoader counting = (p, a) -> {
            calls.incrementAndGet();
            return 5L;
        };
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.cumulative(ACC, counting);
        t.addUsage(ACC, 1L, counting);
        t.cumulative(ACC, counting);
        assertEquals(1, calls.get());
    }

    @Test
    void zeroUsageIsNoOp() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 0L, ZERO);
        assertEquals(0L, t.cumulative(ACC, ZERO));
    }

    @Test
    void negativeUsageThrows() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        assertThrows(IllegalArgumentException.class, () -> t.addUsage(ACC, -1L, ZERO));
    }

    @Test
    void snapshotPendingReturnsOnlyPositiveDeltas() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 100L, ZERO);
        t.addUsage(ACC2, 200L, ZERO);
        t.cumulative(ACC2, (p, a) -> 0L); // tocar otra cuenta no agrega pending
        Map<Address, Long> snap = t.snapshotPending();
        assertEquals(2, snap.size());
        assertEquals(100L, snap.get(ACC));
        assertEquals(200L, snap.get(ACC2));
    }

    @Test
    void applyCommittedMovesPendingToBaseline() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 1000L, ZERO);
        Map<Address, Long> snap = t.snapshotPending();
        t.applyCommitted(snap);
        // cumulative no cambia, pero ahora 1000 está en baseline (no se re-commitearía)
        assertEquals(1000L, t.cumulative(ACC, ZERO));
        assertTrue(t.snapshotPending().isEmpty());
    }

    @Test
    void usageBetweenSnapshotAndCommitIsPreserved() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 1000L, ZERO);
        Map<Address, Long> snap = t.snapshotPending(); // captura 1000
        t.addUsage(ACC, 300L, ZERO);                   // llega más uso antes del commit
        t.applyCommitted(snap);                        // resta solo los 1000 enviados
        assertEquals(1300L, t.cumulative(ACC, ZERO));
        assertEquals(300L, t.snapshotPending().get(ACC)); // los 300 quedan para el próximo commit
    }

    @Test
    void rollToNewPeriodResetsState() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 1000L, (p, a) -> 5000L);
        assertEquals(6000L, t.cumulative(ACC, (p, a) -> 5000L));
        t.rollTo(PERIOD + 1);
        assertEquals(PERIOD + 1, t.currentPeriodId());
        // nuevo período: baseline se rehidrata a 0 (uso on-chain del mes nuevo)
        assertEquals(0L, t.cumulative(ACC, ZERO));
    }

    @Test
    void rollToSamePeriodIsNoOp() {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        t.addUsage(ACC, 1000L, ZERO);
        t.rollTo(PERIOD);
        assertEquals(1000L, t.cumulative(ACC, ZERO));
    }

    @Test
    void concurrentAddsDoNotLoseGas() throws InterruptedException {
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        int threads = 16;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        t.addUsage(ACC, 1L, ZERO);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals((long) threads * perThread, t.cumulative(ACC, ZERO));
    }

    @Test
    void baselineLoadedOncePerAccountUnderConcurrency() throws InterruptedException {
        Map<Address, Integer> loadCounts = new ConcurrentHashMap<>();
        MonthlyUsageTracker.BaselineLoader counting = (p, a) -> {
            loadCounts.merge(a, 1, Integer::sum);
            return 0L;
        };
        MonthlyUsageTracker t = new MonthlyUsageTracker(PERIOD);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    t.cumulative(ACC, counting);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, loadCounts.get(ACC));
    }
}
