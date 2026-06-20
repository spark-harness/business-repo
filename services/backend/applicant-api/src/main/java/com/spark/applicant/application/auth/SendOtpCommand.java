package com.spark.applicant.application.auth;

public record SendOtpCommand(String countryCode, String phone, String idempotencyKey) {}
