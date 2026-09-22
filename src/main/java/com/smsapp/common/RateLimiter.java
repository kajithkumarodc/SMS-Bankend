package com.smsapp.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple, self-hosted, in-memory sliding-window rate limiter (plan Phase 4.5 parts 4/27) -- no
 * external service/dependency (e.g. Redis, a paid API), appropriate for a single self-hosted
 * instance. Used to bound abuse of public, unauthenticated endpoints such as the admission
 * application submission form.
 *
 * <p>Keyed by an arbitrary caller-supplied string (typically the client IP); state is an in-memory
 * map, so limits reset on restart and are not shared across multiple app instances -- an accepted
 * tradeoff for a single-instance self-hosted deployment, not a substitute for a real WAF in a
 * larger/distributed deployment.
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final Map<String, Deque<Instant>> hitsByKey = new ConcurrentHashMap<>();

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one attempt for {@code key} and reports whether it is within the allowed rate.
     * Expired entries (older than {@code windowMinutes}) are pruned as a side effect, so memory
     * never grows unbounded for a key that stops being used.
     */
    public synchronized boolean allow(String key, int maxRequests, int windowMinutes) {
        Instant now = clock.instant();
        Instant windowStart = now.minusSeconds(windowMinutes * 60L);
        Deque<Instant> hits = hitsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());

        while (!hits.isEmpty() && hits.peekFirst().isBefore(windowStart)) {
            hits.pollFirst();
        }

        if (hits.size() >= maxRequests) {
            return false;
        }
        hits.addLast(now);
        return true;
    }
}
