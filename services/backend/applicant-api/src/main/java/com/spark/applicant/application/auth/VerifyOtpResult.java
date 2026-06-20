package com.spark.applicant.application.auth;

import java.time.Duration;

public record VerifyOtpResult(
        String accessToken,
        String refreshToken,
        String applicantId,
        Duration expiresIn,
        Duration refreshExpiresIn) {}
