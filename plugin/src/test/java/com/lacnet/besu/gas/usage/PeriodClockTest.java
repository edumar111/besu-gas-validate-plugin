package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class PeriodClockTest {

    private static long secondsOf(int year, int month, int day, int hour, int min) {
        return ZonedDateTime.of(year, month, day, hour, min, 0, 0, ZoneOffset.UTC)
                .toInstant()
                .getEpochSecond();
    }

    @Test
    void periodIdIsYearTimes12PlusMonth0() {
        assertEquals(2026L * 12 + 4, PeriodClock.periodId(secondsOf(2026, 5, 15, 12, 0)));
        assertEquals(2026L * 12 + 0, PeriodClock.periodId(secondsOf(2026, 1, 1, 0, 0)));
        assertEquals(2026L * 12 + 11, PeriodClock.periodId(secondsOf(2026, 12, 31, 23, 59)));
    }

    @Test
    void sameMonthMapsToSamePeriod() {
        long a = PeriodClock.periodId(secondsOf(2026, 5, 1, 0, 0));
        long b = PeriodClock.periodId(secondsOf(2026, 5, 31, 23, 59));
        assertEquals(a, b);
    }

    @Test
    void monthBoundaryChangesPeriod() {
        long may = PeriodClock.periodId(secondsOf(2026, 5, 31, 23, 59));
        long june = PeriodClock.periodId(secondsOf(2026, 6, 1, 0, 0));
        assertNotEquals(may, june);
        assertEquals(may + 1, june);
    }

    @Test
    void yearBoundaryIsContiguous() {
        long dec = PeriodClock.periodId(secondsOf(2026, 12, 31, 23, 59));
        long jan = PeriodClock.periodId(secondsOf(2027, 1, 1, 0, 0));
        assertEquals(dec + 1, jan);
    }

    @Test
    void usesUtcNotLocalTime() {
        // 2026-06-01 00:30 UTC es junio sin importar la TZ del host.
        long epoch = secondsOf(2026, 6, 1, 0, 30);
        assertEquals(2026L * 12 + 5, PeriodClock.periodId(epoch));
    }

    @Test
    void labelFormatsYearMonth() {
        assertEquals("2026-05", PeriodClock.label(2026L * 12 + 4));
        assertEquals("2026-01", PeriodClock.label(2026L * 12 + 0));
        assertEquals("2026-12", PeriodClock.label(2026L * 12 + 11));
    }

    @Test
    void labelRoundTripsWithPeriodId() {
        long epoch = secondsOf(2026, 5, 15, 12, 0);
        long pid = PeriodClock.periodId(epoch);
        assertEquals("2026-05", PeriodClock.label(pid));
        assertEquals(5, Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).getMonthValue());
    }
}
