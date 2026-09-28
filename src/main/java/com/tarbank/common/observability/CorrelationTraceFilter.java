package com.tarbank.common.observability;

import com.tarbank.common.http.CorrelationIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class CorrelationTraceFilter extends OncePerRequestFilter {
    private final ObservationRegistry observations;

    CorrelationTraceFilter(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Observation observation = observations.getCurrentObservation();
        if (observation != null && CorrelationIdContext.current() != null) {
            observation.highCardinalityKeyValue(
                    "correlation.id", CorrelationIdContext.current().toString());
        }
        filterChain.doFilter(request, response);
    }
}
