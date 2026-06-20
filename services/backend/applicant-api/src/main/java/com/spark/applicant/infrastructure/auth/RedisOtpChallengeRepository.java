package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.OtpChallengeRepository;
import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
public class RedisOtpChallengeRepository implements OtpChallengeRepository {
    private final ExpiringKeyValueStore store;
    private final String keyPrefix;

    public RedisOtpChallengeRepository(ExpiringKeyValueStore store, ApplicantAuthProperties properties) {
        this(store, properties.getKeyPrefix());
    }

    RedisOtpChallengeRepository(ExpiringKeyValueStore store, String keyPrefix) {
        this.store = store;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public Optional<OtpChallenge> find(String challengeId) {
        return store.get(challengeKey(challengeId), OtpChallenge.class);
    }

    @Override
    public Optional<OtpChallenge> findActiveCooldown(PhoneNumber phoneNumber, Instant now) {
        return store.get(cooldownKey(phoneNumber), String.class)
                .flatMap(this::find)
                .filter(challenge -> now.isBefore(challenge.cooldownUntil()));
    }

    @Override
    public void save(OtpChallenge challenge) {
        store.putUntil(challengeKey(challenge.challengeId()), challenge, challenge.expiresAt());
        store.putUntil(cooldownKey(challenge.phoneNumber()), challenge.challengeId(), challenge.cooldownUntil());
    }

    private String challengeKey(String challengeId) {
        return keyPrefix + ":otp:challenge:" + InfrastructureHash.normalize(challengeId);
    }

    private String cooldownKey(PhoneNumber phoneNumber) {
        return keyPrefix + ":otp:phone:" + InfrastructureHash.sha256(phoneNumber.stableKey());
    }

    private String normalizePrefix(String value) {
        String normalized = InfrastructureHash.normalize(value);
        return normalized.isEmpty() ? "applicant-api" : normalized;
    }
}
