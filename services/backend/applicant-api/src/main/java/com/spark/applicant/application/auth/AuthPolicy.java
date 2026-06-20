package com.spark.applicant.application.auth;

import java.time.Duration;

public record AuthPolicy(
        String supportedCountryCode,
        Duration otpTtl,
        Duration resendCooldown,
        int maxOtpAttempts,
        Duration lockDuration,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration idempotencyTtl) {
    public static AuthPolicy defaults() {
        return new AuthPolicy(
                "+852",
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                5,
                Duration.ofMinutes(15),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Duration.ofHours(1));
    }
}
