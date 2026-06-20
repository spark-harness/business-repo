package com.spark.applicant.application.auth;

public record RefreshTokenCommand(String refreshToken, String idempotencyKey) {}
