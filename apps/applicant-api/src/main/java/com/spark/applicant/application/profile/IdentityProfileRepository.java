package com.spark.applicant.application.profile;

import com.spark.applicant.domain.profile.IdentityProfile;
import java.util.Optional;

public interface IdentityProfileRepository {
    void save(IdentityProfile profile);

    Optional<IdentityProfile> findByApplicantId(String applicantId);
}
