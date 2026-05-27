package com.lacnet.besu.gas.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class BlockGasTrackerTest {

    private static final Address ALICE = Address.fromHexString("0x" + "a".repeat(40));
    private static final Address BOB = Address.fromHexString("0x" + "b".repeat(40));
    private static final Address CAROL = Address.fromHexString("0x" + "c".repeat(40));

    @Test
    void estadoInicialEsCero() {
        BlockGasTracker t = new BlockGasTracker();
        assertEquals(BlockGasTracker.UNINITIALIZED_BLOCK, t.currentBlockNumber());
        assertEquals(0L, t.getUsed(ALICE));
        assertEquals(0L, t.totalUsed());
    }

    @Test
    void addAcumulaPorSender() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        t.add(ALICE, 100_000L);
        t.add(ALICE, 50_000L);
        t.add(BOB, 200_000L);

        assertEquals(150_000L, t.getUsed(ALICE));
        assertEquals(200_000L, t.getUsed(BOB));
        assertEquals(0L, t.getUsed(CAROL));
        assertEquals(350_000L, t.totalUsed());
    }

    @Test
    void onBlockChangeResetCuandoCambiaElBloque() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        t.add(ALICE, 100_000L);
        t.add(BOB, 200_000L);
        assertEquals(300_000L, t.totalUsed());

        t.onBlockChange(11L);
        assertEquals(11L, t.currentBlockNumber());
        assertEquals(0L, t.getUsed(ALICE));
        assertEquals(0L, t.getUsed(BOB));
        assertEquals(0L, t.totalUsed());
    }

    @Test
    void onBlockChangeIdempotenteConMismoBloque() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        t.add(ALICE, 100_000L);

        t.onBlockChange(10L);
        // No debe resetear.
        assertEquals(100_000L, t.getUsed(ALICE));
    }

    @Test
    void addCeroEsNoOpYNoCreaEntry() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        t.add(ALICE, 0L);
        assertEquals(0L, t.getUsed(ALICE));
        assertEquals(0L, t.totalUsed());
    }

    @Test
    void addNegativoLanza() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        assertThrows(IllegalArgumentException.class, () -> t.add(ALICE, -1L));
    }

    @Test
    void totalUsedSumaTodosLosSenders() {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);
        t.add(ALICE, 500_000L);
        t.add(BOB, 5_000_000L);
        t.add(CAROL, 10_000_000L);
        // Caso típico: con block gas limit 350M, sobrante PREMIUM = 350M - 15.5M = 334.5M
        assertEquals(15_500_000L, t.totalUsed());
        long blockGasLimit = 350_000_000L;
        long sobrante = blockGasLimit - t.totalUsed();
        assertEquals(334_500_000L, sobrante);
    }

    @Test
    void addsConcurrentesNoSePierden() throws Exception {
        BlockGasTracker t = new BlockGasTracker();
        t.onBlockChange(10L);

        int threads = 16;
        int addsPerThread = 1_000;
        long gasPerAdd = 100L;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    start.await();
                    for (int j = 0; j < addsPerThread; j++) {
                        t.add(ALICE, gasPerAdd);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdownNow();
        }

        long expected = (long) threads * addsPerThread * gasPerAdd;
        assertEquals(expected, t.getUsed(ALICE));
        assertEquals(expected, t.totalUsed());
    }
}
