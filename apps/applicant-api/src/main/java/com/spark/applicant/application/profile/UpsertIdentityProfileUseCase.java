package com.spark.applicant.application.profile;

import com.spark.applicant.domain.profile.HongKongIdentityNumber;
import com.spark.applicant.domain.profile.IdentityProfile;
import com.spark.applicant.domain.profile.IdentityProfileValidationException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

public class UpsertIdentityProfileUseCase {
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private final IdentityProfileRepository repository;
    private final Clock clock;

    public UpsertIdentityProfileUseCase(IdentityProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IdentityProfileResult upsert(UpsertIdentityProfileCommand command) {
        try {
            HongKongIdentityNumber.validate(command.hkidBody(), command.hkidCheckDigit());
            validateAge(command.dateOfBirth());
            java.time.Instant now = clock.instant();
            java.time.Instant createdAt = repository.findByApplicantId(command.applicantId())
                    .map(IdentityProfile::createdAt)
                    .orElse(now);
            IdentityProfile profile = new IdentityProfile(
                    command.applicantId(),
                    command.hkidBody(),
                    command.hkidCheckDigit(),
                    command.firstName(),
                    command.lastName(),
                    command.chineseName(),
                    command.nationality(),
                    command.dateOfBirth(),
                    createdAt,
                    now);
            repository.save(profile);
            return new IdentityProfileResult(profile);
        } catch (IdentityProfileValidationException error) {
            throw new IdentityProfileException(error.getMessage());
        }
    }

    private void validateAge(String dateOfBirth) {
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(dateOfBirth);
        } catch (DateTimeException error) {
            throw new IdentityProfileValidationException("validation_error");
        }
        int years = Period.between(birthDate, LocalDate.now(clock.withZone(HONG_KONG))).getYears();
        if (years < 18 || years > 60) {
            throw new IdentityProfileValidationException("age_out_of_range");
        }
    }
}
