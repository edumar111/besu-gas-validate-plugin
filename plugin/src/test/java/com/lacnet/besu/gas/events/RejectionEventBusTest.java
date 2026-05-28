package com.lacnet.besu.gas.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lacnet.besu.gas.model.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.Test;

class RejectionEventBusTest {

    private static final Address ALICE = Address.fromHexString("0x" + "a".repeat(40));
    private static final Address BOB = Address.fromHexString("0x" + "b".repeat(40));

    /** Clock controlable: avanza solo cuando el test llama a {@link #advance}. */
    private static final class FakeClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(final ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
        void advance(final Duration d) { millis.addAndGet(d.toMillis()); }
    }

    private static Hash hash(final int n) {
        return Hash.fromHexString("0x" + String.format("%064x", n));
    }

    private static RejectionEvent event(final Hash h, final Address sender, final long ts) {
        return new RejectionEvent(
                h, sender, Tier.BASIC, "excedió límite de gas en el bloque",
                100L, 200_000L, 400_000L, 500_000L,
                Instant.ofEpochMilli(ts), RejectionEvent.Source.SELECTOR);
    }

    @Test
    void getDevuelveEmptyParaHashDesconocido() {
        RejectionEventBus bus = new RejectionEventBus();
        assertTrue(bus.get(hash(99)).isEmpty());
    }

    @Test
    void emitYGetRoundtrip() {
        RejectionEventBus bus = new RejectionEventBus();
        RejectionEvent ev = event(hash(1), ALICE, 1_000L);
        bus.emit(ev);
        assertEquals(ev, bus.get(hash(1)).orElseThrow());
    }

    @Test
    void emitSobrescribeElUltimoPorHash() {
        RejectionEventBus bus = new RejectionEventBus();
        bus.emit(event(hash(1), ALICE, 1_000L));
        bus.emit(event(hash(1), ALICE, 2_000L));
        assertEquals(1, bus.size());
        assertEquals(2_000L, bus.get(hash(1)).orElseThrow().timestamp().toEpochMilli());
    }

    @Test
    void listBySenderFiltraYOrdenaDescPorTimestamp() {
        RejectionEventBus bus = new RejectionEventBus();
        bus.emit(event(hash(1), ALICE, 1_000L));
        bus.emit(event(hash(2), BOB, 1_500L));
        bus.emit(event(hash(3), ALICE, 3_000L));
        bus.emit(event(hash(4), ALICE, 2_000L));

        List<RejectionEvent> alice = bus.listBySender(ALICE, 10);
        assertEquals(3, alice.size());
        // Orden desc por timestamp: 3000, 2000, 1000.
        assertEquals(3_000L, alice.get(0).timestamp().toEpochMilli());
        assertEquals(2_000L, alice.get(1).timestamp().toEpochMilli());
        assertEquals(1_000L, alice.get(2).timestamp().toEpochMilli());
    }

    @Test
    void listBySenderClampeaLimit() {
        RejectionEventBus bus = new RejectionEventBus();
        for (int i = 1; i <= 5; i++) {
            bus.emit(event(hash(i), ALICE, i * 1_000L));
        }
        assertEquals(2, bus.listBySender(ALICE, 2).size());
        assertEquals(5, bus.listBySender(ALICE, 999).size());   // clamp a MAX_LIST_LIMIT (100) > 5
        assertEquals(1, bus.listBySender(ALICE, 0).size());     // clamp a 1
    }

    @Test
    void entradaExpiraDespuesDeTtl() {
        FakeClock clock = new FakeClock();
        RejectionEventBus bus = new RejectionEventBus(100, Duration.ofMinutes(5), clock);
        bus.emit(event(hash(1), ALICE, clock.millis()));

        assertTrue(bus.get(hash(1)).isPresent(), "antes del TTL debe estar");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(bus.get(hash(1)).isEmpty(), "después del TTL debe expirar");
    }

    @Test
    void capAcotaPorInsercion() {
        RejectionEventBus bus = new RejectionEventBus(3, Duration.ofMinutes(5), Clock.systemUTC());
        for (int i = 1; i <= 5; i++) {
            bus.emit(event(hash(i), ALICE, i * 1_000L));
        }
        // Cap=3 → solo quedan las 3 más recientes (3,4,5); las 1 y 2 fueron evictadas.
        assertEquals(3, bus.size());
        assertTrue(bus.get(hash(1)).isEmpty());
        assertTrue(bus.get(hash(2)).isEmpty());
        assertFalse(bus.get(hash(5)).isEmpty());
    }
}
