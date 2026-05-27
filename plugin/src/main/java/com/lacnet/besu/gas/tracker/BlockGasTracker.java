package com.lacnet.besu.gas.tracker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.hyperledger.besu.datatypes.Address;

/**
 * Contador de gas consumido por sender dentro del <strong>bloque actual</strong>.
 *
 * <p>Contrato de uso desde el selector:
 * <ol>
 *   <li>En cada invocación del selector, llamar primero {@link #onBlockChange(long)} con el
 *       número del bloque en construcción — si el número cambió, se descartan los contadores
 *       del bloque anterior.</li>
 *   <li>Para decidir si una TX cabe en la cuota, leer {@link #getUsed(Address)} y comparar contra
 *       la quota del tier.</li>
 *   <li>{@link #totalUsed()} se usa para calcular el sobrante disponible para PREMIUM
 *       ({@code blockGasLimit - totalUsed}).</li>
 *   <li>Tras procesar la TX, llamar {@link #add(Address, long)} con el {@code gasUsed} real.</li>
 * </ol>
 *
 * <p>Concurrencia: el reset ({@link #onBlockChange}) está {@code synchronized}. Los updates
 * ({@link #add}) son lock-free vía {@link AtomicLong}; pueden correr en paralelo entre sí y con
 * lecturas. {@link #totalUsed} hace un snapshot consistente-eventual: bajo carga concurrente
 * puede subestimar levemente la suma — eso es <em>seguro</em> porque produce un sobrante
 * conservador para PREMIUM (lo opuesto sería peligroso).
 */
public final class BlockGasTracker {

    /** Sentinel previo a la primera llamada a {@link #onBlockChange}. */
    public static final long UNINITIALIZED_BLOCK = -1L;

    private volatile long currentBlockNumber = UNINITIALIZED_BLOCK;
    private final ConcurrentHashMap<Address, AtomicLong> gasBySender = new ConcurrentHashMap<>();

    /**
     * Si {@code blockNumber} difiere del bloque actual, descarta los contadores y avanza.
     * Idempotente cuando el número es el mismo.
     */
    public synchronized void onBlockChange(final long blockNumber) {
        if (blockNumber == currentBlockNumber) {
            return;
        }
        gasBySender.clear();
        currentBlockNumber = blockNumber;
    }

    public long currentBlockNumber() {
        return currentBlockNumber;
    }

    public long getUsed(final Address sender) {
        AtomicLong counter = gasBySender.get(sender);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Suma del gas usado por todos los senders en el bloque actual. Usado por el selector para
     * calcular el sobrante disponible para PREMIUM: {@code sobrante = blockGasLimit - totalUsed()}.
     */
    public long totalUsed() {
        long sum = 0L;
        for (AtomicLong counter : gasBySender.values()) {
            sum += counter.get();
        }
        return sum;
    }

    /**
     * Suma {@code gas} al contador del sender. Aceptamos 0 (no-op) y rechazamos negativos —
     * un gas usado negativo nunca tiene sentido y suele indicar bug del caller.
     */
    public void add(final Address sender, final long gas) {
        if (gas < 0) {
            throw new IllegalArgumentException("gas must be >= 0, got: " + gas);
        }
        if (gas == 0) {
            return;
        }
        gasBySender.computeIfAbsent(sender, k -> new AtomicLong()).addAndGet(gas);
    }
}
