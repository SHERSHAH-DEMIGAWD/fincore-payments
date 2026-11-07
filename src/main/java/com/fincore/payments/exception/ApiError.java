package com.fincore.payments.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> fieldErrors
) {
}
