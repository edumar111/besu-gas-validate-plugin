package com.lacnet.besu.gas.events;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

/**
 * Store en memoria de los últimos rechazos del plugin, consultable por el
 * cliente vía JSON-RPC. Lo escriben el selector y el validator; lo leen los
 * handlers de {@code gasMembership_getRejection} y
 * {@code gasMembership_listRejectionsBySender}.
 *
 * <p>Implementación: {@link LinkedHashMap} acotado por inserción (cap
 * {@code maxEntries}) con expiración por TTL chequeada en lectura. Sin
 * dependencias externas — las deps de Besu son {@code compileOnly} y no quería
 * shadowear una librería de cache en el JAR.
 *
 * <p>Thread-safe: todo acceso al mapa está bajo {@code synchronized(this)}. El
 * cache de Besu invoca el selector/validator desde múltiples threads, así que
 * el bus debe tolerar escrituras concurrentes.
 *
 * <p>No persiste en disco — si Besu reinicia, el log se pierde. Suficiente para
 * que un cliente que pollea reconecte dentro de la ventana de TTL.
 */
public class RejectionEventBus {

    static final int DEFAULT_MAX_ENTRIES = 10_000;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    static final int MAX_LIST_LIMIT = 100;

    private final int maxEntries;
    private final long ttlMillis;
    private final Clock clock;
    private final LinkedHashMap<Hash, Entry> byHash;

    private record Entry(RejectionEvent event, long insertedAtMillis) {}

    public RejectionEventBus() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, Clock.systemUTC());
    }

    /** Visible para tests — permite cap chico, TTL chico y clock controlable. */
    RejectionEventBus(final int maxEntries, final Duration ttl, final Clock clock) {
        this.maxEntries = maxEntries;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
        this.byHash = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Hash, Entry> eldest) {
                return size() > RejectionEventBus.this.maxEntries;
            }
        };
    }

    /** Registra (o sobrescribe) el último rechazo de la TX. */
    public synchronized void emit(final RejectionEvent event) {
        byHash.put(event.txHash(), new Entry(event, clock.millis()));
    }

    /** Último rechazo de la TX, o vacío si no hay registro vigente. */
    public synchronized Optional<RejectionEvent> get(final Hash txHash) {
        Entry e = byHash.get(txHash);
        if (e == null) {
            return Optional.empty();
        }
        if (isExpired(e)) {
            byHash.remove(txHash);
            return Optional.empty();
        }
        return Optional.of(e.event());
    }

    /**
     * Rechazos vigentes de un sender, ordenados de más reciente a más viejo.
     * {@code limit} se clampa al rango [1, {@value #MAX_LIST_LIMIT}].
     */
    public synchronized List<RejectionEvent> listBySender(final Address sender, final int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        long now = clock.millis();
        List<RejectionEvent> matches = new ArrayList<>();
        for (Entry e : byHash.values()) {
            if (now - e.insertedAtMillis() > ttlMillis) {
                continue;
            }
            if (e.event().sender().equals(sender)) {
                matches.add(e.event());
            }
        }
        matches.sort(Comparator.comparing(RejectionEvent::timestamp).reversed());
        if (matches.size() > capped) {
            return new ArrayList<>(matches.subList(0, capped));
        }
        return matches;
    }

    private boolean isExpired(final Entry e) {
        return clock.millis() - e.insertedAtMillis() > ttlMillis;
    }

    /** Visible para tests. */
    synchronized int size() {
        return byHash.size();
    }
}
