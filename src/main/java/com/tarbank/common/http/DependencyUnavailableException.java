package com.tarbank.common.http;

import org.springframework.http.HttpStatus;

public final class DependencyUnavailableException extends ApiException {
    public DependencyUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
              "A required service is temporarily unavailable.", "TAR-INFRA-002");
    }
}
