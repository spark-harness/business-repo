package com.spark.applicant.application.auth;

import java.time.Duration;

public record SendOtpResult(String challengeId, Duration expiresIn, Duration resendAfter) {}
