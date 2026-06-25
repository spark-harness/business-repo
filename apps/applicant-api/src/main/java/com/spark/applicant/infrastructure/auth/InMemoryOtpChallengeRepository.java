package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.OtpChallengeRepository;
import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(
        prefix = "spark.applicant.auth",
        name = "runtime-store",
        havingValue = "in-memory",
        matchIfMissing = true)
public class InMemoryOtpChallengeRepository implements OtpChallengeRepository {
    private final Clock clock;
    private final Map<String, OtpChallenge> challengesById = new ConcurrentHashMap<>();

    public InMemoryOtpChallengeRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<OtpChallenge> find(String challengeId) {
        return Optional.ofNullable(challengesById.get(challengeId));
    }

    @Override
    public Optional<OtpChallenge> findActiveCooldown(PhoneNumber phoneNumber, Instant now) {
        return challengesById.values().stream()
                .filter(challenge -> challenge.phoneNumber().equals(phoneNumber))
                .filter(challenge -> now.isBefore(challenge.cooldownUntil()))
                .findFirst();
    }

    @Override
    public void save(OtpChallenge challenge) {
        challengesById.put(challenge.challengeId(), challenge);
    }

    public int challengeCount() {
        pruneExpired();
        return challengesById.size();
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        challengesById.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
}
