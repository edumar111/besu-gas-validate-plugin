package com.lacnet.besu.gas.usage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.hyperledger.besu.datatypes.Address;

/**
 * Registro in-memory de cuentas bloqueadas temporalmente por exceder la cuota mensual (Fase 2).
 *
 * <p>El bloqueo tiene un TTL (por defecto 5 min). Mientras está vigente, el validator y el selector
 * rechazan <b>todas</b> las TX de la cuenta. Es deliberadamente in-memory: una penalización corta
 * que se pierde en un restart del nodo es aceptable (el restart re-evaluaría la cuota igual).
 *
 * <p>Thread-safe vía {@link ConcurrentHashMap}. La expiración es lazy (al consultar) — no hace falta
 * un thread de limpieza para la corrección; opcionalmente {@link #cleanup(long)} purga entradas
 * vencidas para acotar memoria.
 */
public final class BlockedAddressRegistry {

    /** account → epoch millis hasta el cual está bloqueada (exclusivo). */
    private final Map<Address, Long> blockedUntilMillis = new ConcurrentHashMap<>();

    /**
     * Bloquea {@code account} hasta {@code nowMillis + ttlMillis}. Si ya estaba bloqueada, extiende
     * el bloqueo solo si el nuevo vencimiento es posterior (nunca lo acorta).
     *
     * @param account cuenta a bloquear
     * @param nowMillis instante actual (wall-clock) en epoch millis
     * @param ttlMillis duración del bloqueo en millis (debe ser ≥ 0)
     */
    public void block(final Address account, final long nowMillis, final long ttlMillis) {
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("ttlMillis no puede ser negativo: " + ttlMillis);
        }
        final long until = nowMillis + ttlMillis;
        blockedUntilMillis.merge(account, until, Math::max);
    }

    /**
     * @param account cuenta a consultar
     * @param nowMillis instante actual en epoch millis
     * @return {@code true} si la cuenta está bloqueada en {@code nowMillis}
     */
    public boolean isBlocked(final Address account, final long nowMillis) {
        Long until = blockedUntilMillis.get(account);
        if (until == null) {
            return false;
        }
        if (until > nowMillis) {
            return true;
        }
        // Vencido: limpieza lazy. compute para no borrar un bloqueo recién extendido por otro thread.
        blockedUntilMillis.computeIfPresent(account, (k, v) -> v > nowMillis ? v : null);
        return false;
    }

    /**
     * @param account cuenta a consultar
     * @param nowMillis instante actual en epoch millis
     * @return el epoch millis hasta el que está bloqueada, si lo está; vacío en caso contrario
     */
    public Optional<Long> blockedUntil(final Address account, final long nowMillis) {
        Long until = blockedUntilMillis.get(account);
        return (until != null && until > nowMillis) ? Optional.of(until) : Optional.empty();
    }

    /**
     * Purga las entradas vencidas en {@code nowMillis}. Opcional (la corrección no depende de esto).
     *
     * @param nowMillis instante actual en epoch millis
     */
    public void cleanup(final long nowMillis) {
        blockedUntilMillis.values().removeIf(until -> until <= nowMillis);
    }
}
