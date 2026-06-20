package com.spark.applicant.application.auth.port;

import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.applicant.domain.applicant.PhoneNumber;
import java.time.Instant;
import java.util.Optional;

public interface OtpChallengeRepository {
    Optional<OtpChallenge> find(String challengeId);

    Optional<OtpChallenge> findActiveCooldown(PhoneNumber phoneNumber, Instant now);

    void save(OtpChallenge challenge);
}
