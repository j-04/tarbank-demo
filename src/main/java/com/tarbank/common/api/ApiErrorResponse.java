package com.tarbank.common.api;

import java.util.List;
import java.util.UUID;

public record ApiErrorResponse(ErrorDetails error, UUID correlationId) {

    public record ErrorDetails(String code, String message, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String code, String message) {
    }
}
