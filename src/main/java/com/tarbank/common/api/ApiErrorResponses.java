package com.tarbank.common.api;

import java.util.List;
import java.util.UUID;

public final class ApiErrorResponses {

    private ApiErrorResponses() {
    }

    public static ApiErrorResponse validation(UUID correlationId, List<ApiErrorResponse.FieldError> fieldErrors) {
        return new ApiErrorResponse(
                new ApiErrorResponse.ErrorDetails("VALIDATION_ERROR", "The request is invalid.", fieldErrors),
                correlationId);
    }

    public static ApiErrorResponse internalError(UUID correlationId) {
        return new ApiErrorResponse(
                new ApiErrorResponse.ErrorDetails("INTERNAL_ERROR", "An unexpected error occurred.", List.of()),
                correlationId);
    }
}
