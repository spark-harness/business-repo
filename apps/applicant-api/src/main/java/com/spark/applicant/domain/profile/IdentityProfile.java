package com.spark.applicant.domain.profile;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

public record IdentityProfile(
        String applicantId,
        String hkidBody,
        String hkidCheckDigit,
        String firstName,
        String lastName,
        String chineseName,
        Nationality nationality,
        String dateOfBirth,
        Instant createdAt,
        Instant updatedAt) {
    private static final Pattern HKID_BODY = Pattern.compile("[A-Z][0-9]{6}");
    private static final Pattern HKID_CHECK_DIGIT = Pattern.compile("[0-9A]");
    private static final Pattern ENGLISH_NAME = Pattern.compile("[A-Za-z]+");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    public IdentityProfile {
        applicantId = requireText(applicantId, "validation_error");
        hkidBody = requireText(hkidBody, "validation_error").toUpperCase(Locale.ROOT);
        hkidCheckDigit = requireText(hkidCheckDigit, "validation_error").toUpperCase(Locale.ROOT);
        firstName = requireText(firstName, "validation_error");
        lastName = requireText(lastName, "validation_error");
        chineseName = requireText(chineseName, "validation_error");
        if (nationality == null || createdAt == null || updatedAt == null) {
            throw new IdentityProfileValidationException("validation_error");
        }
        if (!HKID_BODY.matcher(hkidBody).matches() || !HKID_CHECK_DIGIT.matcher(hkidCheckDigit).matches()) {
            throw new IdentityProfileValidationException("hkid_invalid");
        }
        if (!ENGLISH_NAME.matcher(firstName).matches() || !ENGLISH_NAME.matcher(lastName).matches()) {
            throw new IdentityProfileValidationException("validation_error");
        }
        if (!DATE.matcher(requireText(dateOfBirth, "validation_error")).matches()) {
            throw new IdentityProfileValidationException("validation_error");
        }
    }

    private static String requireText(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new IdentityProfileValidationException(code);
        }
        return value.trim();
    }
}
