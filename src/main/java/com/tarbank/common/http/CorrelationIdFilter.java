package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.common.api.ApiErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.CloseableThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";

    private static final Pattern UUID_HEADER_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JsonMapper jsonMapper;

    public CorrelationIdFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId;
        try {
            correlationId = readOrCreateCorrelationId(request);
        } catch (IllegalArgumentException exception) {
            writeMalformedHeaderResponse(response);
            return;
        }

        response.setHeader(HEADER_NAME, correlationId.toString());
        CorrelationIdContext.set(correlationId);
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.put("correlationId", correlationId.toString())) {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdContext.clear();
        }
    }

    private UUID readOrCreateCorrelationId(HttpServletRequest request) {
        String suppliedValue = request.getHeader(HEADER_NAME);
        if (suppliedValue == null) {
            return UUID.randomUUID();
        }
        if (!UUID_HEADER_PATTERN.matcher(suppliedValue)
                                .matches()) {
            throw new IllegalArgumentException("Invalid correlation ID format.");
        }
        return UUID.fromString(suppliedValue);
    }

    private void writeMalformedHeaderResponse(HttpServletResponse response) throws IOException {
        ApiErrorResponse error = ApiErrorResponses.validation(
                null,
                List.of(new ApiErrorResponse.FieldError(HEADER_NAME, "UUID_REQUIRED", "Must be a UUID.")));
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream()
                .print(jsonMapper.writeValueAsString(error));
        response.flushBuffer();
    }
}