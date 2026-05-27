package com.lacnet.besu.gas.cache;

import com.lacnet.besu.gas.model.Tier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;

/**
 * Caché de tier por cuenta con TTL medido en bloques. Una entry se considera vigente si
 * {@code currentBlock - cachedAtBlock <= ttlBlocks}.
 *
 * <p>El caching es obligatorio para evitar disparar un {@code eth_call} interno por cada TX que
 * pasa por el selector — con block period de 2 s y throughput de cientos de TX/s eso saturaría
 * al simulator.
 *
 * <p>La API principal para callers es {@link #getOrLoad(Address, long, Function)}, que implementa
 * el patrón cache-aside con protección anti-stampede: si N threads piden la misma cuenta cuando
 * la cache está fría, sólo uno ejecuta el loader. {@link #get(Address, long)} y
 * {@link #put(Address, Tier, long)} se exponen para casos donde el caller necesita controlar el
 * flujo (típicamente tests).
 */
public final class TierCache {

    private final ConcurrentHashMap<Address, CacheEntry> entries = new ConcurrentHashMap<>();
    private final int ttlBlocks;

    public TierCache(final int ttlBlocks) {
        if (ttlBlocks <= 0) {
            throw new IllegalArgumentException("ttlBlocks must be > 0, got: " + ttlBlocks);
        }
        this.ttlBlocks = ttlBlocks;
    }

    /**
     * Devuelve el tier cacheado para {@code account} si su entry está vigente respecto a
     * {@code currentBlock}; {@link Optional#empty()} si no hay entry o ya expiró.
     */
    public Optional<Tier> get(final Address account, final long currentBlock) {
        CacheEntry entry = entries.get(account);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry, currentBlock)) {
            return Optional.empty();
        }
        return Optional.of(entry.tier());
    }

    /** Guarda explícitamente. Usá {@link #getOrLoad} en hot paths para evitar stampede. */
    public void put(final Address account, final Tier tier, final long currentBlock) {
        entries.put(account, new CacheEntry(tier, currentBlock));
    }

    /**
     * Patrón cache-aside con bloqueo por clave: si la entry está fresca, la devuelve; si no,
     * ejecuta {@code loader} bajo el lock de la key (vía {@link ConcurrentHashMap#compute}) y
     * cachea el resultado.
     *
     * <p>Bajo concurrencia con misma clave, el loader se invoca exactamente una vez; con claves
     * distintas, los loaders corren en paralelo.
     */
    public Tier getOrLoad(
            final Address account,
            final long currentBlock,
            final Function<Address, Tier> loader) {
        CacheEntry refreshed = entries.compute(account, (key, existing) -> {
            if (existing != null && !isExpired(existing, currentBlock)) {
                return existing;
            }
            return new CacheEntry(loader.apply(key), currentBlock);
        });
        return refreshed.tier();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private boolean isExpired(final CacheEntry entry, final long currentBlock) {
        return currentBlock - entry.cachedAtBlock() > ttlBlocks;
    }

    public record CacheEntry(Tier tier, long cachedAtBlock) {}
}
