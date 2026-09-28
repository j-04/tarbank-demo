package com.tarbank.common.resilience;

import com.tarbank.common.config.RateLimitProperties;
import com.tarbank.common.config.RateLimitProperties.Policy;
import com.tarbank.common.http.RateLimitExceededException;
import com.tarbank.common.observability.OperationalMetrics;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RateLimitService {
    private final RateLimitProperties properties;
    private final RedisTokenBucketRateLimiter buckets;
    private final OperationalMetrics metrics;

    public RateLimitService(RateLimitProperties properties,
                            RedisTokenBucketRateLimiter buckets,
                            OperationalMetrics metrics) {
        this.properties = properties;
        this.buckets = buckets;
        this.metrics = metrics;
    }

    public void checkAnonymous(String directAddress) {
        check("anonymous", directAddress, properties.anonymous());
    }

    public void checkLogin(String directAddress, String username) {
        check("login_ip", directAddress, properties.loginIp());
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        check("login_username", normalized, properties.loginUsername());
    }

    public void checkAuthenticated(Long userId) {
        check("authenticated", userId.toString(), properties.authenticated());
    }

    public void checkMoneyAccount(String accountNumber) {
        check("money_account", accountNumber, properties.moneyAccount());
    }

    private void check(String group, String identifier, Policy policy) {
        if (!properties.enabled()) return;
        RedisTokenBucketRateLimiter.Decision decision = buckets.consume(group, identifier, policy);
        if (!decision.allowed()) {
            metrics.rateLimitRejected(group);
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
