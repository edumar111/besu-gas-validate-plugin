package com.lacnet.besu.gas.usage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.hyperledger.besu.datatypes.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contador del consumo de gas <b>mensual</b> por cuenta para el período en curso (Fase 2).
 *
 * <p>Modelo híbrido: para cada cuenta el consumo acumulado es
 * {@code cumulative = committedBaseline + pendingDelta}, donde:
 * <ul>
 *   <li><b>committedBaseline</b> — lo ya persistido on-chain en {@code UsageMeter}. Se rehidrata
 *       lazy desde la cadena la primera vez que se toca la cuenta en el período (anti-stampede vía
 *       {@code computeIfAbsent}, igual que {@code TierCache}). Tras un restart, esto recupera el uso
 *       real.</li>
 *   <li><b>pendingDelta</b> — el uso visto en bloques recientes pero todavía no commiteado. Lo
 *       alimenta el {@code UsageBlockListener} en cada bloque (en todos los nodos). El nodo recorder
 *       lo drena periódicamente, lo envía al {@code UsageMeter} y lo mueve al baseline.</li>
 * </ul>
 *
 * <p>El período se identifica con un {@code periodId} (mes calendario UTC, ver {@link PeriodClock}).
 * Al cambiar de período, {@link #rollTo(long)} resetea todo (los baselines del nuevo período se
 * rehidratan a 0, que es el uso on-chain inicial del mes nuevo).
 */
public final class MonthlyUsageTracker {

    private static final Logger LOG = LoggerFactory.getLogger(MonthlyUsageTracker.class);

    /** Carga el baseline (uso ya commiteado on-chain) de una cuenta para un período. */
    @FunctionalInterface
    public interface BaselineLoader {
        long load(long periodId, Address account);
    }

    private static final class AccountUsage {
        final AtomicLong baseline;
        final AtomicLong pending = new AtomicLong(0L);

        AccountUsage(final long baseline) {
            this.baseline = new AtomicLong(baseline);
        }
    }

    private volatile long periodId;
    private final Map<Address, AccountUsage> usage = new ConcurrentHashMap<>();

    public MonthlyUsageTracker(final long initialPeriodId) {
        this.periodId = initialPeriodId;
    }

    /** @return el período actualmente activo (mes calendario UTC). */
    public long currentPeriodId() {
        return periodId;
    }

    /**
     * Cambia el período activo si difiere del actual, descartando el estado del período anterior.
     * El recorder debe drenar/commitear el pending del período viejo <b>antes</b> de llamar esto.
     * Rara (mensual) → {@code synchronized} es suficiente.
     *
     * @param newPeriodId nuevo período
     */
    public synchronized void rollTo(final long newPeriodId) {
        if (newPeriodId != periodId) {
            LOG.info("MonthlyUsageTracker: cambio de período {} → {} (reset de contadores)",
                    PeriodClock.label(periodId), PeriodClock.label(newPeriodId));
            usage.clear();
            periodId = newPeriodId;
        }
    }

    private AccountUsage entry(final Address account, final BaselineLoader loader) {
        return usage.computeIfAbsent(account, k -> new AccountUsage(loader.load(periodId, k)));
    }

    /**
     * Consumo acumulado (baseline + pending) de la cuenta en el período actual, rehidratando el
     * baseline desde la cadena si es la primera vez que se la ve.
     *
     * @param account cuenta
     * @param loader cargador del baseline on-chain
     * @return gas acumulado en el período
     */
    public long cumulative(final Address account, final BaselineLoader loader) {
        AccountUsage e = entry(account, loader);
        return e.baseline.get() + e.pending.get();
    }

    /**
     * Suma {@code gas} al pendingDelta de la cuenta en el período actual.
     *
     * @param account cuenta
     * @param gas gas consumido (≥ 0); 0 es no-op
     * @param loader cargador del baseline on-chain (para inicializar la entry si es nueva)
     */
    public void addUsage(final Address account, final long gas, final BaselineLoader loader) {
        if (gas < 0) {
            throw new IllegalArgumentException("gas no puede ser negativo: " + gas);
        }
        if (gas == 0) {
            return;
        }
        entry(account, loader).pending.addAndGet(gas);
    }

    /**
     * Snapshot de los pendingDelta no commiteados (&gt; 0) en el momento de la llamada. No los
     * resetea: el recorder primero envía la TX y, al confirmarse, llama {@link #applyCommitted}.
     *
     * @return mapa cuenta → pendingDelta para las cuentas con delta &gt; 0
     */
    public Map<Address, Long> snapshotPending() {
        Map<Address, Long> snapshot = new HashMap<>();
        for (Map.Entry<Address, AccountUsage> en : usage.entrySet()) {
            long p = en.getValue().pending.get();
            if (p > 0) {
                snapshot.put(en.getKey(), p);
            }
        }
        return snapshot;
    }

    /**
     * Aplica los deltas ya persistidos on-chain: mueve cada delta de pending a baseline. Resta
     * exactamente el valor enviado (no el pending actual), de modo que el uso acumulado entre el
     * snapshot y la confirmación se conserva en pending.
     *
     * @param committedDeltas mapa cuenta → delta efectivamente commiteado (de {@link #snapshotPending})
     */
    public void applyCommitted(final Map<Address, Long> committedDeltas) {
        for (Map.Entry<Address, Long> en : committedDeltas.entrySet()) {
            AccountUsage e = usage.get(en.getKey());
            if (e != null) {
                e.baseline.addAndGet(en.getValue());
                e.pending.addAndGet(-en.getValue());
            }
        }
    }

    /** Visible para tests: setea el baseline de una cuenta sin pasar por el loader. */
    void setBaselineForTest(final Address account, final long baseline) {
        usage.compute(account, (k, v) -> {
            if (v == null) {
                return new AccountUsage(baseline);
            }
            v.baseline.set(baseline);
            return v;
        });
    }
}
