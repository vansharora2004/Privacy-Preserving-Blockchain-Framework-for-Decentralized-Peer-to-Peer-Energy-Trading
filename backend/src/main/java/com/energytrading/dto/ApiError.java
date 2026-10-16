package com.energytrading.dto;

import java.time.Instant;
import java.util.List;

/** Uniform error response body. */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       List<FieldErrorView> fieldErrors) {

    public record FieldErrorView(String field, String message) {
    }
}
