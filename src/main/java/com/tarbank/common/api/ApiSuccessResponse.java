package com.tarbank.common.api;

import java.util.UUID;

public record ApiSuccessResponse<T>(T data, UUID correlationId) {
}
