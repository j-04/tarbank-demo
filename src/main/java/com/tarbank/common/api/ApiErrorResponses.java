package com.tarbank.common.api;

import java.util.List;
import java.util.UUID;

public final class ApiErrorResponses {
    private ApiErrorResponses() {
    }

    public static ApiErrorResponse validation(UUID id, List<ApiErrorResponse.FieldError> fields) {
        return error(id, "VALIDATION_ERROR", "The request is invalid.", fields);
    }

    public static ApiErrorResponse internalError(UUID id) {
        return error(id, "INTERNAL_ERROR", "An unexpected error occurred.", List.of());
    }

    public static ApiErrorResponse error(UUID id, String code, String message) {
        return error(id, code, message, List.of());
    }

    private static ApiErrorResponse error(UUID id, String code, String message, List<ApiErrorResponse.FieldError> fields) {
        return new ApiErrorResponse(new ApiErrorResponse.ErrorDetails(code, message, fields), id);
    }
}