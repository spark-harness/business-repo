package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.ApplicantRepository;
import com.spark.applicant.domain.applicant.Applicant;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(
        prefix = "spark.applicant.auth",
        name = "runtime-store",
        havingValue = "in-memory",
        matchIfMissing = true)
public class InMemoryApplicantRepository implements ApplicantRepository {
    private final Clock clock;
    private final Map<String, Applicant> applicantsByPhone = new ConcurrentHashMap<>();

    public InMemoryApplicantRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Applicant findOrCreateByPhoneNumber(PhoneNumber phoneNumber) {
        return applicantsByPhone.computeIfAbsent(phoneNumber.stableKey(), ignored -> {
            Instant now = clock.instant();
            return new Applicant("applicant_" + UUID.randomUUID(), phoneNumber, now, now);
        });
    }

    public int count() {
        return applicantsByPhone.size();
    }
}
