package com.spark.applicant.domain.applicant;

public record PhoneNumber(String countryCode, String phone) {
    public PhoneNumber {
        countryCode = normalize(countryCode);
        phone = normalize(phone);
    }

    public String stableKey() {
        return countryCode + ":" + phone;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
