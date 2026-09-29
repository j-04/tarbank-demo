package com.tarbank.common.http;

import com.tarbank.common.resilience.RateLimitService;
import com.tarbank.security.application.TarbankPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;

    private final ApiSecurityErrorWriter errors;

    public AuthenticatedRateLimitFilter(RateLimitService limits,
                                        ApiSecurityErrorWriter errors) {
        this.limits = limits;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object principal = SecurityContextHolder.getContext()
                                                .getAuthentication() == null
                ? null : SecurityContextHolder.getContext()
                                              .getAuthentication()
                                              .getPrincipal();
        if (!(principal instanceof TarbankPrincipal tarbankPrincipal)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            limits.checkAuthenticated(tarbankPrincipal.userId());
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
