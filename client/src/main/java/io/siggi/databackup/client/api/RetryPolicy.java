package io.siggi.databackup.client.api;

import java.time.Duration;

/**
 * How {@link ServerApi} retries transient failures (connection errors, timeouts, truncated responses and {@code 5xx}
 * statuses): up to {@code maxAttempts} attempts in total, waiting {@code initialDelay} before the first retry and
 * multiplying the wait by {@code multiplier} after each one, capped at {@code maxDelay}.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be at least initialDelay");
        }
        if (!(multiplier >= 1.0)) throw new IllegalArgumentException("multiplier must be at least 1");
    }

    /**
     * 5 attempts, waiting 1, 2, 4 and 8 seconds between them (at most 30 seconds).
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0);
    }

    /**
     * A single attempt, no retries.
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0);
    }

    /**
     * The wait before retry number {@code retry} (1 for the first retry).
     */
    public Duration delayBeforeRetry(int retry) {
        double millis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
        long capped = (long) Math.min(millis, (double) maxDelay.toMillis());
        return Duration.ofMillis(capped);
    }
}
