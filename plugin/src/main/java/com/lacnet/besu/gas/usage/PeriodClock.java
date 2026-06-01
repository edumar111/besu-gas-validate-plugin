package com.lacnet.besu.gas.usage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Deriva el identificador de período de cuota mensual a partir de un timestamp.
 *
 * <p>El período es el <b>mes calendario UTC</b>: {@code periodId = year*12 + (month-1)}. Se computa
 * sobre el <b>timestamp del bloque</b> (no wall-clock) para que todos los nodos coincidan en el
 * corte de período y para que matchee lo que se commitea on-chain al {@code UsageMeter}.
 *
 * <p>Ejemplos: enero 1970 (year=1970, month=1) → {@code 1970*12 + 0 = 23640};
 * mayo 2026 → {@code 2026*12 + 4 = 24316}.
 */
public final class PeriodClock {

    private PeriodClock() {}

    /**
     * @param epochSeconds timestamp en segundos desde epoch (típicamente el del header del bloque)
     * @return periodId del mes calendario UTC que contiene ese instante
     */
    public static long periodId(final long epochSeconds) {
        ZonedDateTime utc = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
        return (long) utc.getYear() * 12L + (utc.getMonthValue() - 1);
    }

    /**
     * Etiqueta legible {@code "YYYY-MM"} de un periodId (para logs y respuestas RPC).
     *
     * @param periodId identificador de período (year*12 + month0)
     * @return etiqueta {@code YYYY-MM}
     */
    public static String label(final long periodId) {
        long year = Math.floorDiv(periodId, 12);
        long month0 = Math.floorMod(periodId, 12);
        return String.format("%04d-%02d", year, month0 + 1);
    }
}
