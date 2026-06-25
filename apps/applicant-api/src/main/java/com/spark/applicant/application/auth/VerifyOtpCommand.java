package com.spark.applicant.application.auth;

public record VerifyOtpCommand(String challengeId, String code, String idempotencyKey) {}
