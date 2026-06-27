package com.spark.common.spring.security;

public record RequestPrincipal(String applicantId) {
    public RequestPrincipal {
        if (applicantId == null || applicantId.isBlank()) {
            throw new IllegalArgumentException("applicantId is required");
        }
    }
}
