package io.siggi.databackup.util;

import io.siggi.tools.SimpleDateTime;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

public class TimeUtil {
    private static final SimpleDateTime sdt;

    static {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneId.of("UTC"));
        sdt = SimpleDateTime.of(dtf);
    }

    public static String toString(long time) {
        return sdt.format(time);
    }

    public static long fromString(String time) {
        return sdt.parse(time);
    }
}
