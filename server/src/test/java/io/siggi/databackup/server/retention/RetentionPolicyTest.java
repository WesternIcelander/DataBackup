package io.siggi.databackup.server.retention;

import io.siggi.databackup.server.ServerConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetentionPolicyTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long t(String isoLocal) {
        return LocalDateTime.parse(isoLocal).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static List<Long> times(String... isoLocal) {
        List<Long> list = new ArrayList<>();
        for (String s : isoLocal) list.add(t(s));
        return list;
    }

    private static RetentionPolicy.Decision<Long> apply(RetentionPolicy policy, List<Long> times, ZoneId zone) {
        return policy.apply(times, x -> x, zone);
    }

    private static void assertSplit(RetentionPolicy.Decision<Long> decision, List<Long> all, List<Long> expectedKept) {
        List<Long> kept = new ArrayList<>(expectedKept);
        kept.sort(Collections.reverseOrder());
        List<Long> deleted = new ArrayList<>(all);
        deleted.removeAll(expectedKept);
        deleted.sort(Collections.reverseOrder());
        assertEquals(kept, decision.keep(), "kept");
        assertEquals(deleted, decision.delete(), "deleted");
    }

    @Test
    void emptyListKeepsAndDeletesNothing() {
        RetentionPolicy.Decision<Long> decision = apply(new RetentionPolicy(3, 3, 3, 3), List.of(), UTC);
        assertTrue(decision.keep().isEmpty());
        assertTrue(decision.delete().isEmpty());
    }

    @Test
    void allZeroStillKeepsTheNewest() {
        List<Long> all = times("2026-01-01T10:00:00", "2026-01-03T10:00:00", "2026-01-02T10:00:00");
        assertSplit(apply(new RetentionPolicy(0, 0, 0, 0), all, UTC), all, times("2026-01-03T10:00:00"));
    }

    @Test
    void singleBackupIsKept() {
        List<Long> all = times("2026-01-01T10:00:00");
        assertSplit(apply(new RetentionPolicy(0, 0, 0, 0), all, UTC), all, all);
    }

    @Test
    void keepLastKeepsTheNewestN() {
        List<Long> all = times("2026-01-01T01:00:00", "2026-01-01T02:00:00", "2026-01-01T03:00:00",
                "2026-01-01T04:00:00", "2026-01-01T05:00:00");
        assertSplit(apply(new RetentionPolicy(3, 0, 0, 0), all, UTC), all,
                times("2026-01-01T03:00:00", "2026-01-01T04:00:00", "2026-01-01T05:00:00"));
    }

    @Test
    void keepLastLargerThanCountKeepsEverything() {
        List<Long> all = times("2026-01-01T01:00:00", "2026-01-02T01:00:00");
        assertSplit(apply(new RetentionPolicy(10, 0, 0, 0), all, UTC), all, all);
    }

    @Test
    void inputOrderDoesNotMatterAndResultsAreNewestFirst() {
        List<Long> all = times("2026-01-02T01:00:00", "2026-01-05T01:00:00", "2026-01-01T01:00:00",
                "2026-01-04T01:00:00", "2026-01-03T01:00:00");
        RetentionPolicy.Decision<Long> decision = apply(new RetentionPolicy(2, 0, 0, 0), all, UTC);
        assertEquals(times("2026-01-05T01:00:00", "2026-01-04T01:00:00"), decision.keep());
        assertEquals(times("2026-01-03T01:00:00", "2026-01-02T01:00:00", "2026-01-01T01:00:00"), decision.delete());
    }

    @Test
    void dailyKeepsTheNewestOfEachDayAndSkipsDaysWithoutBackups() {
        List<Long> all = times(
                "2026-03-10T08:00:00", "2026-03-10T20:00:00", // newest day
                "2026-03-09T01:00:00", "2026-03-09T23:59:59",
                // nothing on 03-08 or 03-07
                "2026-03-06T12:00:00",
                "2026-03-05T12:00:00", "2026-03-05T13:00:00");
        assertSplit(apply(new RetentionPolicy(0, 3, 0, 0), all, UTC), all,
                times("2026-03-10T20:00:00", "2026-03-09T23:59:59", "2026-03-06T12:00:00"));
    }

    @Test
    void weeklyUsesIsoWeeksStartingOnMonday() {
        // 2026-03-08 is a Sunday, 2026-03-09 a Monday.
        List<Long> all = times(
                "2026-03-09T00:00:00", // week 11 (Monday)
                "2026-03-08T23:00:00", "2026-03-02T00:00:00", // week 10 (Sunday, Monday)
                "2026-03-01T12:00:00", // week 9 (Sunday)
                "2026-02-20T12:00:00"); // week 8
        assertSplit(apply(new RetentionPolicy(0, 0, 3, 0), all, UTC), all,
                times("2026-03-09T00:00:00", "2026-03-08T23:00:00", "2026-03-01T12:00:00"));
    }

    @Test
    void weeklyAcrossTheYearBoundary() {
        // 2025-12-29 (Monday) to 2026-01-04 (Sunday) is ISO week 2026-W01.
        List<Long> all = times("2026-01-04T10:00:00", "2025-12-29T10:00:00", "2025-12-28T10:00:00");
        assertSplit(apply(new RetentionPolicy(0, 0, 2, 0), all, UTC), all,
                times("2026-01-04T10:00:00", "2025-12-28T10:00:00"));
    }

    @Test
    void monthlyKeepsTheNewestOfEachMonth() {
        List<Long> all = times(
                "2026-05-02T00:00:00", "2026-05-31T23:00:00",
                "2026-04-30T23:59:59", "2026-04-01T00:00:00",
                "2026-02-15T00:00:00", // no backup in March
                "2026-01-15T00:00:00",
                "2025-12-31T00:00:00");
        assertSplit(apply(new RetentionPolicy(0, 0, 0, 3), all, UTC), all,
                times("2026-05-31T23:00:00", "2026-04-30T23:59:59", "2026-02-15T00:00:00"));
    }

    @Test
    void periodsAreCountedInTheGivenTimeZone() {
        // In UTC these are two days; in New York (UTC-5 in January) both are on 2026-01-01.
        List<Long> all = times("2026-01-01T23:30:00", "2026-01-02T00:30:00");
        RetentionPolicy daily = new RetentionPolicy(0, 2, 0, 0);
        assertSplit(apply(daily, all, UTC), all, all);
        assertSplit(apply(daily, all, ZoneId.of("America/New_York")), all, times("2026-01-02T00:30:00"));
    }

    @Test
    void rulesCountIndependentlyAndCombine() {
        List<Long> all = times(
                "2026-03-10T03:00:00", "2026-03-10T02:00:00", "2026-03-10T01:00:00",
                "2026-03-09T05:00:00", "2026-03-09T04:00:00",
                "2026-03-01T05:00:00",
                "2026-02-10T05:00:00");
        // keepLast 2 keeps 03-10 03:00 and 02:00; daily 2 keeps 03-10 03:00 (already kept) and 03-09 05:00, so the
        // backups kept by keepLast don't use up days; monthly 2 keeps 03-10 03:00 and 02-10.
        assertSplit(apply(new RetentionPolicy(2, 2, 0, 2), all, UTC), all,
                times("2026-03-10T03:00:00", "2026-03-10T02:00:00", "2026-03-09T05:00:00", "2026-02-10T05:00:00"));
    }

    @Test
    void negativeSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(0, 0, 0, -1));
    }

    @Test
    void fromConfigTreatsUnsetAsZero() {
        assertEquals(new RetentionPolicy(5, 0, 2, 0),
                RetentionPolicy.of(new ServerConfig.Retention(5, null, 2, null)));
        assertEquals(new RetentionPolicy(10, 14, 8, 24),
                RetentionPolicy.of(ServerConfig.defaults().getRetention()));
    }

    @Test
    void hourlyBackupsForTwoYearsWithTheDefaultPolicy() {
        List<Long> all = new ArrayList<>();
        long start = t("2024-06-01T00:15:00");
        long end = t("2026-06-01T12:15:00");
        for (long time = start; time <= end; time += 3600_000L) all.add(time);
        RetentionPolicy.Decision<Long> decision = apply(new RetentionPolicy(10, 14, 8, 24), all, UTC);
        // Check against the reference implementation and some fixed points.
        assertEquals(reference(new RetentionPolicy(10, 14, 8, 24), all, UTC), new HashSet<>(decision.keep()));
        assertTrue(decision.keep().contains(end));
        assertTrue(decision.keep().contains(t("2026-05-31T23:15:00")), "newest of 05-31 (day and month)");
        assertTrue(decision.keep().contains(t("2024-07-31T23:15:00")), "24th month back");
        assertTrue(!decision.keep().contains(t("2024-06-30T23:15:00")), "25th month back");
        assertEquals(all.size(), decision.keep().size() + decision.delete().size());
    }

    @Test
    void matchesAReferenceImplementationOnRandomHistories() {
        Random random = new Random(1234);
        ZoneId[] zones = {UTC, ZoneId.of("America/New_York"), ZoneId.of("Pacific/Auckland")};
        for (int round = 0; round < 300; round++) {
            int count = random.nextInt(60);
            List<Long> all = new ArrayList<>();
            Set<Long> unique = new HashSet<>();
            long base = t("2025-01-01T00:00:00");
            while (all.size() < count) {
                long time = base + (long) (random.nextDouble() * 400L * 86400_000L) / 1000L * 1000L;
                if (unique.add(time)) all.add(time);
            }
            RetentionPolicy policy = new RetentionPolicy(random.nextInt(5), random.nextInt(10), random.nextInt(6),
                    random.nextInt(8));
            ZoneId zone = zones[random.nextInt(zones.length)];
            RetentionPolicy.Decision<Long> decision = apply(policy, all, zone);
            assertEquals(reference(policy, all, zone), new HashSet<>(decision.keep()),
                    "round " + round + " " + policy + " " + zone);
            assertEquals(all.size(), decision.keep().size() + decision.delete().size());
        }
    }

    /**
     * A straightforward restatement of the rules: the newest N, plus the newest backup of each of the newest D/W/M
     * periods that have backups, plus always the newest.
     */
    private static Set<Long> reference(RetentionPolicy policy, List<Long> all, ZoneId zone) {
        Set<Long> keep = new HashSet<>();
        if (all.isEmpty()) return keep;
        TreeSet<Long> sorted = new TreeSet<>(all);
        keep.add(sorted.last());
        int i = 0;
        for (Long time : sorted.descendingSet()) {
            if (i++ >= policy.keepLast()) break;
            keep.add(time);
        }
        keepPeriods(keep, sorted, policy.keepDaily(), d -> d.toString(), zone);
        keepPeriods(keep, sorted, policy.keepWeekly(),
                d -> d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), zone);
        keepPeriods(keep, sorted, policy.keepMonthly(), d -> d.getYear() + "-" + d.getMonthValue(), zone);
        return keep;
    }

    private static void keepPeriods(Set<Long> keep, TreeSet<Long> sorted, int count,
                                    Function<LocalDate, String> period, ZoneId zone) {
        Map<String, Long> newestPerPeriod = new HashMap<>();
        for (Long time : sorted) {
            newestPerPeriod.merge(period.apply(Instant.ofEpochMilli(time).atZone(zone).toLocalDate()), time,
                    Math::max);
        }
        List<Long> newest = new ArrayList<>(newestPerPeriod.values());
        newest.sort(Collections.reverseOrder());
        keep.addAll(newest.subList(0, Math.min(count, newest.size())));
    }
}
