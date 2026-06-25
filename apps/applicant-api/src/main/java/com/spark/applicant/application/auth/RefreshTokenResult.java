package com.spark.applicant.application.auth;

import java.time.Duration;

public record RefreshTokenResult(String accessToken, Duration expiresIn) {}
