package io.siggi.databackup.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeUtilTest {
    @Test
    void formatsInUtc() {
        assertEquals("1970-01-01-00-00-00", TimeUtil.toString(0L));
        assertEquals("2026-11-12-13-14-15", TimeUtil.toString(1794489255000L));
    }

    @Test
    void roundTrips() {
        long[] times = {0L, 1000L, 1794489255000L, 946684799000L, 4102444800000L};
        for (long time : times) {
            assertEquals(time, TimeUtil.fromString(TimeUtil.toString(time)));
        }
    }

    @Test
    void roundTripDropsMilliseconds() {
        assertEquals(1794489255000L, TimeUtil.fromString(TimeUtil.toString(1794489255999L)));
    }

    @Test
    void invalidInputReturnsMinusOne() {
        assertEquals(-1L, TimeUtil.fromString(null));
        assertEquals(-1L, TimeUtil.fromString(""));
        assertEquals(-1L, TimeUtil.fromString("not a time"));
        assertEquals(-1L, TimeUtil.fromString("2026-11-12"));
        assertEquals(-1L, TimeUtil.fromString("2026-13-12-00-00-00"));
        assertEquals(-1L, TimeUtil.fromString("2026-11-12-25-00-00"));
        assertEquals(-1L, TimeUtil.fromString("2026-11-12-13-14-15.tmp"));
        assertEquals(-1L, TimeUtil.fromString(".pending"));
    }
}
