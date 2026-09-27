package com.tarbank.common.http;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    private final String code;

    private final String safeMessage;

    private final String internalCode;

    public ApiException(HttpStatus status, String code, String safeMessage, String internalCode) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.safeMessage = safeMessage;
        this.internalCode = internalCode;
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
}