package com.lacnet.besu.gas.usage;

import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.model.Tier;
import java.time.Clock;
import org.hyperledger.besu.datatypes.Address;

/**
 * Enforcement de la cuota <b>mensual</b> + bloqueo de 5 min (Fase 2), compartido por el selector
 * (al armar el bloque) y el validator (al admitir al txpool). Un solo punto de decisión para que
 * ambos componentes apliquen exactamente la misma política.
 *
 * <p>Decisión para una cuenta no-WHITELISTED:
 * <ul>
 *   <li><b>BLOCKED</b> — la cuenta está dentro de una ventana de bloqueo vigente (excedió su cuota
 *       mensual hace &lt; 5 min). Todas sus TX se rechazan.</li>
 *   <li><b>EXCEEDED</b> — esta TX haría que el acumulado mensual supere la cuota del tier. Se
 *       rechaza y se <b>inicia</b> (o extiende) la ventana de bloqueo.</li>
 *   <li><b>OK</b> — cabe en la cuota mensual; sigue el enforcement per-block (Fase 1).</li>
 * </ul>
 *
 * <p>WHITELISTED siempre es OK (bypass). El bloqueo usa wall-clock ({@link Clock}); el conteo de
 * uso viene del {@link MonthlyUsageTracker} (in-memory + baseline on-chain rehidratado lazy).
 */
public final class MonthlyQuotaGuard {

    /** Resultado de evaluar la cuota mensual de una TX. */
    public enum Decision {
        OK,
        BLOCKED,
        EXCEEDED
    }

    private final GasMembershipConfig config;
    private final MonthlyUsageTracker tracker;
    private final BlockedAddressRegistry blocked;
    private final MonthlyUsageTracker.BaselineLoader baselineLoader;
    private final Clock clock;

    public MonthlyQuotaGuard(
            final GasMembershipConfig config,
            final MonthlyUsageTracker tracker,
            final BlockedAddressRegistry blocked,
            final MonthlyUsageTracker.BaselineLoader baselineLoader,
            final Clock clock) {
        this.config = config;
        this.tracker = tracker;
        this.blocked = blocked;
        this.baselineLoader = baselineLoader;
        this.clock = clock;
    }

    /**
     * Evalúa la cuota mensual de una TX. Si la TX excede la cuota, esta llamada <b>bloquea</b> la
     * cuenta como efecto colateral (la penalización del SLA).
     *
     * @param sender cuenta emisora
     * @param tier tier del sender (ya resuelto por el caller vía la TierCache compartida)
     * @param txGasLimit gasLimit declarado de la TX (proyección pesimista del uso)
     * @return la decisión de enforcement
     */
    public Decision check(final Address sender, final Tier tier, final long txGasLimit) {
        if (tier == Tier.WHITELISTED) {
            return Decision.OK;
        }
        final long now = clock.millis();
        if (blocked.isBlocked(sender, now)) {
            return Decision.BLOCKED;
        }
        final long monthlyQuota = config.getMonthlyQuota(tier);
        final long cumulative = tracker.cumulative(sender, baselineLoader);
        // Proyección pesimista: sumamos el gasLimit completo (igual que el enforcement per-block).
        if (cumulative + txGasLimit > monthlyQuota) {
            blocked.block(sender, now, config.getMonthlyBlockDurationMillis());
            return Decision.EXCEEDED;
        }
        return Decision.OK;
    }

    /** Cuota mensual del tier (para enriquecer el RejectionEvent del caller). */
    public long monthlyQuotaOf(final Tier tier) {
        return config.getMonthlyQuota(tier);
    }
}
