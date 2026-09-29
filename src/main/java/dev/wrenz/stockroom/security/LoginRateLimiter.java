package dev.wrenz.stockroom.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.wrenz.stockroom.common.RateLimitedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory, single-instance limits (spec section 6). Both caches are bounded and entries expire after
 * the window, counted from the first attempt. Several instances would need Redis instead (see README).
 */
@Component
public class LoginRateLimiter {

    private final Cache<String, AtomicInteger> attemptsByIp;
    private final Cache<String, AtomicInteger> failuresByEmail;
    private final int maxAttemptsPerIp;
    private final int maxFailuresPerEmail;

    public LoginRateLimiter(RateLimitProperties properties) {
        this.attemptsByIp = Caffeine.newBuilder().maximumSize(properties.maxKeys()).expireAfterWrite(properties.window()).build();
        this.failuresByEmail = Caffeine.newBuilder().maximumSize(properties.maxKeys()).expireAfterWrite(properties.window()).build();
        this.maxAttemptsPerIp = properties.maxAttemptsPerIp();
        this.maxFailuresPerEmail = properties.maxFailuresPerEmail();
    }

    /** Counts every login/register attempt from this IP, successful or not. */
    public void checkIp(String ip) {
        if (attemptsByIp.get(ip, key -> new AtomicInteger()).incrementAndGet() > maxAttemptsPerIp) {
            throw new RateLimitedException();
        }
    }

    public void checkEmailNotLocked(String email) {
        AtomicInteger failures = failuresByEmail.getIfPresent(email);
        if (failures != null && failures.get() >= maxFailuresPerEmail) {
            throw new RateLimitedException();
        }
    }

    public void recordFailure(String email) {
        failuresByEmail.get(email, key -> new AtomicInteger()).incrementAndGet();
    }

    /** For tests only: every test starts with empty counters. */
    public void clear() {
        attemptsByIp.invalidateAll();
        failuresByEmail.invalidateAll();
    }
}
