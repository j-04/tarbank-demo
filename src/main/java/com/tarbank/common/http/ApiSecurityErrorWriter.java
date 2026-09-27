package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class ApiSecurityErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiSecurityErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        UUID correlationId = CorrelationIdContext.current();
        if (correlationId != null) {
            response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse error = new ApiErrorResponse(
                new ApiErrorResponse.ErrorDetails(code, message, List.of()), correlationId);
        response.getOutputStream().print(jsonMapper.writeValueAsString(error));
        response.flushBuffer();
    }
}