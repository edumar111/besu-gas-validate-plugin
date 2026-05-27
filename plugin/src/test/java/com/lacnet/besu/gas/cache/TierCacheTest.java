package com.lacnet.besu.gas.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lacnet.besu.gas.model.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class TierCacheTest {

    private static final Address ADDR = Address.fromHexString("0x" + "a".repeat(40));
    private static final Address ADDR_2 = Address.fromHexString("0x" + "b".repeat(40));

    @Test
    void constructorRechazaTtlNoPositivo() {
        assertThrows(IllegalArgumentException.class, () -> new TierCache(0));
        assertThrows(IllegalArgumentException.class, () -> new TierCache(-1));
    }

    @Test
    void getRetornaEmptyCuandoNoHayEntry() {
        TierCache cache = new TierCache(50);
        assertTrue(cache.get(ADDR, 100L).isEmpty());
    }

    @Test
    void getRetornaTierCuandoEntryEsFrescaEnBloqueExacto() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.BASIC, 100L);
        assertEquals(Optional.of(Tier.BASIC), cache.get(ADDR, 100L));
    }

    @Test
    void getRetornaTierEnElLimiteSuperiorDelTtl() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.STANDARD, 100L);
        // boundary inclusivo: cachedAt + ttlBlocks debe seguir vigente
        assertEquals(Optional.of(Tier.STANDARD), cache.get(ADDR, 150L));
    }

    @Test
    void getRetornaEmptyJustoFueraDelTtl() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.PREMIUM, 100L);
        assertTrue(cache.get(ADDR, 151L).isEmpty());
    }

    @Test
    void putSobrescribeEntryYResetTtl() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.BASIC, 100L);
        cache.put(ADDR, Tier.PREMIUM, 200L);
        assertEquals(Optional.of(Tier.PREMIUM), cache.get(ADDR, 200L));
        // El TTL se cuenta desde la última put (200), no desde la primera (100).
        assertEquals(Optional.of(Tier.PREMIUM), cache.get(ADDR, 250L));
        assertTrue(cache.get(ADDR, 251L).isEmpty());
    }

    @Test
    void getOrLoadEjecutaLoaderCuandoMiss() {
        TierCache cache = new TierCache(50);
        AtomicInteger calls = new AtomicInteger();
        Tier result = cache.getOrLoad(ADDR, 100L, a -> {
            calls.incrementAndGet();
            return Tier.STANDARD;
        });
        assertEquals(Tier.STANDARD, result);
        assertEquals(1, calls.get());
        // Y queda cacheado.
        assertEquals(Optional.of(Tier.STANDARD), cache.get(ADDR, 100L));
    }

    @Test
    void getOrLoadNoEjecutaLoaderConHitFresco() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.PREMIUM, 100L);
        AtomicInteger calls = new AtomicInteger();
        Tier result = cache.getOrLoad(ADDR, 100L, a -> {
            calls.incrementAndGet();
            return Tier.NONE;
        });
        assertEquals(Tier.PREMIUM, result);
        assertEquals(0, calls.get(), "loader no debe correr con hit fresco");
    }

    @Test
    void getOrLoadRecargaCuandoEntryExpiro() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.BASIC, 100L);
        AtomicInteger calls = new AtomicInteger();
        // block 200 → cached estaba en 100, ttl=50 → expirada
        Tier result = cache.getOrLoad(ADDR, 200L, a -> {
            calls.incrementAndGet();
            return Tier.STANDARD;
        });
        assertEquals(Tier.STANDARD, result);
        assertEquals(1, calls.get());
    }

    @Test
    void getOrLoadConcurrenteConMismaKeyEjecutaLoaderUnaVez() throws Exception {
        TierCache cache = new TierCache(50);
        AtomicInteger calls = new AtomicInteger();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Tier>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    start.await();
                    return cache.getOrLoad(ADDR, 100L, a -> {
                        // sleep para forzar que múltiples threads lleguen al compute
                        // mientras el primero todavía está cargando.
                        calls.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Tier.PREMIUM;
                    });
                }));
            }
            start.countDown();
            for (Future<Tier> f : futures) {
                assertEquals(Tier.PREMIUM, f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get(), "anti-stampede: loader debe correr exactamente una vez");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void getOrLoadConcurrenteConKeysDistintasNoSeSerializa() throws Exception {
        TierCache cache = new TierCache(50);
        int n = 16;
        Address[] addrs = new Address[n];
        for (int i = 0; i < n; i++) {
            // direcciones distintas
            String hex = String.format("0x%040x", i + 1);
            addrs[i] = Address.fromHexString(hex);
        }
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(n);
        try {
            List<Future<Tier>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final Address a = addrs[i];
                futures.add(exec.submit(() -> {
                    start.await();
                    return cache.getOrLoad(a, 100L, k -> {
                        calls.incrementAndGet();
                        return Tier.BASIC;
                    });
                }));
            }
            start.countDown();
            for (Future<Tier> f : futures) {
                assertEquals(Tier.BASIC, f.get(5, TimeUnit.SECONDS));
            }
            // claves distintas: el loader corre n veces (una por cada key única).
            assertEquals(n, calls.get());
            assertEquals(n, cache.size());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void clearVaciaLaCache() {
        TierCache cache = new TierCache(50);
        cache.put(ADDR, Tier.BASIC, 100L);
        cache.put(ADDR_2, Tier.PREMIUM, 100L);
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.get(ADDR, 100L).isEmpty());
    }
}
