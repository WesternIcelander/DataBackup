package io.siggi.databackup.server.retention;

import io.siggi.databackup.server.ServerConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Decides which backups to keep. Every rule works back from the newest backup, independently of the others, and a
 * backup is kept if any rule keeps it:
 *
 * <ul>
 *     <li>{@code keepLast}: the newest N backups.</li>
 *     <li>{@code keepDaily}: the newest backup of each of the newest D days that have a backup.</li>
 *     <li>{@code keepWeekly}: the same for ISO weeks (Monday to Sunday).</li>
 *     <li>{@code keepMonthly}: the same for calendar months.</li>
 * </ul>
 *
 * <p>Days, weeks and months are calendar periods in the given time zone. Periods without a backup don't count, so a
 * machine that was off for a while keeps D days of backups, not D days of calendar. The newest backup is always kept,
 * even when every setting is 0.
 */
public record RetentionPolicy(int keepLast, int keepDaily, int keepWeekly, int keepMonthly) {

    public RetentionPolicy {
        if (keepLast < 0 || keepDaily < 0 || keepWeekly < 0 || keepMonthly < 0) {
            throw new IllegalArgumentException("Retention settings must not be negative");
        }
    }

    /**
     * Creates a policy from config settings; unset values count as 0.
     */
    public static RetentionPolicy of(ServerConfig.Retention retention) {
        return new RetentionPolicy(orZero(retention.getKeepLast()), orZero(retention.getKeepDaily()),
                orZero(retention.getKeepWeekly()), orZero(retention.getKeepMonthly()));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * The outcome of {@link #apply}: both lists are sorted newest first.
     */
    public record Decision<T>(List<T> keep, List<T> delete) {
    }

    /**
     * Splits {@code items} into the ones to keep and the ones to delete.
     *
     * @param items the backups, in any order
     * @param time  each backup's time in milliseconds since the epoch
     * @param zone  the time zone that days, weeks and months are counted in
     */
    public <T> Decision<T> apply(List<T> items, ToLongFunction<T> time, ZoneId zone) {
        Objects.requireNonNull(zone);
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(time).reversed()); // stable: equal times keep their order
        int n = sorted.size();
        boolean[] keep = new boolean[n];
        long[] day = new long[n];
        long[] week = new long[n];
        long[] month = new long[n];
        for (int i = 0; i < n; i++) {
            LocalDate date = Instant.ofEpochMilli(time.applyAsLong(sorted.get(i))).atZone(zone).toLocalDate();
            day[i] = date.toEpochDay();
            week[i] = date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            month[i] = date.getYear() * 12L + date.getMonthValue() - 1;
        }
        if (n > 0) keep[0] = true;
        for (int i = 0; i < Math.min(keepLast, n); i++) keep[i] = true;
        keepNewestPerPeriod(day, keepDaily, keep);
        keepNewestPerPeriod(week, keepWeekly, keep);
        keepNewestPerPeriod(month, keepMonthly, keep);
        List<T> kept = new ArrayList<>();
        List<T> deleted = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            (keep[i] ? kept : deleted).add(sorted.get(i));
        }
        return new Decision<>(Collections.unmodifiableList(kept), Collections.unmodifiableList(deleted));
    }

    /**
     * Keeps the first (newest) item of each of the first {@code count} distinct periods.
     */
    private static void keepNewestPerPeriod(long[] periods, int count, boolean[] keep) {
        int seen = 0;
        for (int i = 0; i < periods.length && seen < count; i++) {
            if (i == 0 || periods[i] != periods[i - 1]) {
                keep[i] = true;
                seen++;
            }
        }
    }
}
