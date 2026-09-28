package com.tarbank.common.http;

import com.tarbank.common.resilience.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AnonymousRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    private final ApiSecurityErrorWriter errors;

    public AnonymousRateLimitFilter(RateLimitService limits,
                                    ApiSecurityErrorWriter errors) {
        this.limits = limits;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            limits.checkAnonymous(request.getRemoteAddr());
            filterChain.doFilter(request, response);
        } catch (RateLimitExceededException exception) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
            errors.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                         "The request rate limit has been exceeded.");
        } catch (DependencyUnavailableException exception) {
            errors.write(response, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                         "A required service is temporarily unavailable.");
        }
    }
}
