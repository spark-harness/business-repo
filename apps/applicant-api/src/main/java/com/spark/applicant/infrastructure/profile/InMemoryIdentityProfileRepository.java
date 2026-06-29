package com.spark.applicant.infrastructure.profile;

import com.spark.applicant.application.profile.IdentityProfileRepository;
import com.spark.applicant.domain.profile.IdentityProfile;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
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
public class InMemoryIdentityProfileRepository implements IdentityProfileRepository {
    private final Map<String, IdentityProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public void save(IdentityProfile profile) {
        profiles.put(profile.applicantId(), profile);
    }

    @Override
    public Optional<IdentityProfile> findByApplicantId(String applicantId) {
        return Optional.ofNullable(profiles.get(applicantId));
    }
}
