package com.spark.applicant.application.auth;

import java.time.Instant;

public record TokenRecord(String token, String applicantId, Instant expiresAt) {}
