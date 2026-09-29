package com.tarbank.common.resilience;

import com.tarbank.common.config.RateLimitProperties.Policy;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.http.DependencyUnavailableException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class RedisTokenBucketRateLimiter {
    private static final String SCRIPT = """
            -- Input contract:
            --   KEYS[1] = hashed bucket key for one policy group and caller identity
            --   ARGV[1] = maximum number of tokens the bucket can hold
            --   ARGV[2] = tokens restored per second; convert it to tokens per millisecond
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2]) / 1000.0

            -- Use Redis server time so every application instance observes the same clock.
            -- Redis TIME returns seconds and microseconds; bucket state is stored in milliseconds.
            local current = redis.call('TIME')
            local now_ms = tonumber(current[1]) * 1000 + math.floor(tonumber(current[2]) / 1000)

            -- Each bucket is a hash containing its fractional token balance and last update time.
            -- A missing bucket starts full, allowing an initial burst up to the configured capacity.
            local state = redis.call('HMGET', key, 'tokens', 'updated_at')
            local tokens = tonumber(state[1])
            local updated_at = tonumber(state[2])
            if tokens == nil then tokens = capacity end
            if updated_at == nil then updated_at = now_ms end

            -- Restore tokens earned since the previous request. Clamp elapsed time at zero to
            -- tolerate a Redis clock correction, and cap the result so idle buckets never overfill.
            local elapsed = math.max(0, now_ms - updated_at)
            tokens = math.min(capacity, tokens + elapsed * refill_per_ms)

            -- One request costs one token. When the bucket is empty, calculate the exact delay
            -- until one complete token is available; Java rounds this value up to Retry-After seconds.
            local allowed = 0
            local retry_ms = 0
            if tokens >= 1.0 then
                allowed = 1
                tokens = tokens - 1.0
            else
                retry_ms = math.ceil((1.0 - tokens) / refill_per_ms)
            end

            -- Persist the post-decision state. Expire inactive buckets after twice the time needed
            -- to refill an empty bucket, with a one-second minimum, to bound Redis memory usage.
            redis.call('HSET', key, 'tokens', tostring(tokens), 'updated_at', tostring(now_ms))
            local ttl_ms = math.max(1000, math.ceil((capacity / refill_per_ms) * 2))
            redis.call('PEXPIRE', key, ttl_ms)

            -- Return [1, 0] when allowed, or [0, millisecondsUntilNextToken] when rejected.
            -- The whole script executes atomically in Redis, so concurrent callers cannot overspend.
            return {allowed, retry_ms}
            """;

    private final StringRedisTemplate redis;

    private final ObservationRegistry observations;

    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(SCRIPT, List.class);

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis,
                                       ObservationRegistry observations) {
        this.redis = redis;
        this.observations = observations;
    }

    /**
     * Atomically refills and consumes one distributed token using Redis server time. Hashing the
     * identifier keeps usernames, account numbers, and network addresses out of Redis keys. Redis
     * outages fail closed as dependency-unavailable errors instead of silently disabling limits.
     *
     * @param group policy namespace separating independent endpoint limits
     * @param identifier caller identity within that namespace
     * @param policy bucket capacity and per-second refill rate
     * @return whether the request is allowed and whole retry-after seconds when it is denied
     */
    public Decision consume(String group,
                            String identifier,
                            Policy policy) {
        String key = "rate-limit:" + group + ":" + sha256(identifier);
        Observation observation = Observation.createNotStarted("tarbank.redis", observations)
                                             .lowCardinalityKeyValue("operation", "rate_limit");
        if (CorrelationIdContext.current() != null) {
            observation.highCardinalityKeyValue("correlation.id", CorrelationIdContext.current()
                                                                                      .toString());
        }
        try {
            List<?> result = observation.observe(
                    () -> redis.execute(script, List.of(key), Integer.toString(policy.capacity()),
                                        Double.toString(policy.refillTokensPerSecond())));
            if (result == null || result.size() != 2) {
                throw new DependencyUnavailableException();
            }
            boolean allowed = ((Number) result.getFirst()).longValue() == 1;
            long retryMillis = ((Number) result.get(1)).longValue();
            return new Decision(allowed, allowed ? 0 : Math.max(1, (retryMillis + 999) / 1000));
        } catch (DataAccessException exception) {
            throw new DependencyUnavailableException();
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                                         .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of()
                            .formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Rate-limit key hashing is unavailable.", exception);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
