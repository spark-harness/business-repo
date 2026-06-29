package com.spark.applicant.application.profile;

import com.spark.applicant.domain.profile.Nationality;

public record UpsertIdentityProfileCommand(
        String applicantId,
        String hkidBody,
        String hkidCheckDigit,
        String firstName,
        String lastName,
        String chineseName,
        Nationality nationality,
        String dateOfBirth) {
    public UpsertIdentityProfileCommand withHkidCheckDigit(String value) {
        return new UpsertIdentityProfileCommand(
                applicantId, hkidBody, value, firstName, lastName, chineseName, nationality, dateOfBirth);
    }

    public UpsertIdentityProfileCommand withFirstName(String value) {
        return new UpsertIdentityProfileCommand(
                applicantId, hkidBody, hkidCheckDigit, value, lastName, chineseName, nationality, dateOfBirth);
    }

    public UpsertIdentityProfileCommand withDateOfBirth(String value) {
        return new UpsertIdentityProfileCommand(
                applicantId, hkidBody, hkidCheckDigit, firstName, lastName, chineseName, nationality, value);
    }
}
