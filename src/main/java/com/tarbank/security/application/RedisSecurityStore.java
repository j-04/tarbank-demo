package com.tarbank.security.application;

import com.tarbank.common.http.CorrelationIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class RedisSecurityStore {
    private final StringRedisTemplate redis;

    private final ObservationRegistry observations;

    public RedisSecurityStore(StringRedisTemplate redis,
                              ObservationRegistry observations) {
        this.redis = redis;
        this.observations = observations;
    }

    public boolean isInvalidated(UUID tokenId) {
        return observe("token_lookup", () -> Boolean.TRUE.equals(
                redis.hasKey("jwt:invalidated:" + tokenId)));
    }

    public void invalidate(UUID tokenId,
                           Duration remaining) {
        observe("token_invalidation", () -> {
            redis.opsForValue()
                 .set("jwt:invalidated:" + tokenId, "1", remaining);
            return null;
        });
    }

    private <T> T observe(String operation,
                          java.util.function.Supplier<T> action) {
        Observation observation = Observation.createNotStarted("tarbank.redis", observations)
                                             .lowCardinalityKeyValue("operation", operation);
        if (CorrelationIdContext.current() != null) {
            observation.highCardinalityKeyValue(
                    "correlation.id", CorrelationIdContext.current()
                                                          .toString());
        }
        return observation.observe(action);
    }
}
