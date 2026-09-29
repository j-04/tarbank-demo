package com.tarbank.common.http;

import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiRequestHeaders {
    public static final String UUID_V4_PATTERN =
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    public static final String ENTITY_ETAG_PATTERN =
            "^(?:\"(?:customer|account)-v(?:0|[1-9][0-9]*)\"|(?:customer|account)-v(?:0|[1-9][0-9]*))$";

    private static final Pattern UUID_V4 = Pattern.compile(UUID_V4_PATTERN);

    private ApiRequestHeaders() {
    }

    public static UUID parseUuidV4(String value) {
        try {
            if (value == null || !UUID_V4.matcher(value).matches()) {
                throw new IllegalArgumentException();
            }
            UUID key = UUID.fromString(value);
            if (key.version() != 4 || key.variant() != 2) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception exception) {
            throw validation();
        }
    }

    public static int parseEntityVersion(String value,
                                         String entityName,
                                         String missingHeaderInternalCode) {
        if (value == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                                   "If-Match is required.", missingHeaderInternalCode);
        }
        String normalized = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
        Matcher matcher = Pattern.compile("^" + Pattern.quote(entityName) + "-v(0|[1-9][0-9]*)$")
                                 .matcher(normalized);
        if (!matcher.matches()) {
            throw validation();
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    public static String entityTag(String entityName,
                                   int version) {
        return '"' + entityName + "-v" + version + '"';
    }

    private static ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                "The request is invalid.", "TAR-API-001");
    }
}
