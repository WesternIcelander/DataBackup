package io.siggi.databackup.util;

import io.siggi.tools.SimpleDateTime;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtil {
    private static final SimpleDateTime sdt;

    static {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneId.of("UTC"));
        sdt = SimpleDateTime.of(dtf);
    }

    public static String toString(long time) {
        return sdt.format(time);
    }

    /**
     * Parses a time created by {@link #toString(long)}.
     *
     * @return the time in milliseconds, or -1 if {@code time} is null or not a valid time
     */
    public static long fromString(String time) {
        if (time == null) return -1L;
        try {
            return sdt.parse(time);
        } catch (DateTimeException e) {
            return -1L;
        }
    }
}
