package com.spark.applicant.domain.applicant;

import java.time.Instant;

public record OtpChallenge(
        String challengeId,
        PhoneNumber phoneNumber,
        String codeHash,
        Instant expiresAt,
        Instant cooldownUntil,
        int failedAttempts,
        Instant lockedUntil,
        boolean verified) {
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public OtpChallenge withFailedAttempts(int attempts) {
        return new OtpChallenge(
                challengeId, phoneNumber, codeHash, expiresAt, cooldownUntil, attempts, lockedUntil, verified);
    }

    public OtpChallenge lockedUntil(Instant until) {
        return new OtpChallenge(challengeId, phoneNumber, codeHash, expiresAt, cooldownUntil, failedAttempts, until, verified);
    }

    public OtpChallenge markVerified() {
        return new OtpChallenge(challengeId, phoneNumber, codeHash, expiresAt, cooldownUntil, failedAttempts, lockedUntil, true);
    }
}
