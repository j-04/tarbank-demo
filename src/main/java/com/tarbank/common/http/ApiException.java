package com.tarbank.common.http;

import com.tarbank.common.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    private final String code;

    private final String safeMessage;

    private final String internalCode;

    private final List<ApiErrorResponse.FieldError> fieldErrors;

    public ApiException(HttpStatus status,
                        String code,
                        String safeMessage,
                        String internalCode) {
        this(status, code, safeMessage, internalCode, List.of());
    }

    public ApiException(HttpStatus status,
                        String code,
                        String safeMessage,
                        String internalCode,
                        List<ApiErrorResponse.FieldError> fieldErrors) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.safeMessage = safeMessage;
        this.internalCode = internalCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public String getInternalCode() {
        return internalCode;
    }

    public List<ApiErrorResponse.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}