package com.spark.applicant.domain.profile;

import java.util.Locale;

public final class HongKongIdentityNumber {
    private HongKongIdentityNumber() {}

    public static void validate(String body, String checkDigit) {
        String normalizedBody = body == null ? "" : body.trim().toUpperCase(Locale.ROOT);
        String normalizedCheckDigit = checkDigit == null ? "" : checkDigit.trim().toUpperCase(Locale.ROOT);
        if (!normalizedBody.matches("[A-Z][0-9]{6}") || !normalizedCheckDigit.matches("[0-9A]")) {
            throw new IdentityProfileValidationException("hkid_invalid");
        }
        if (!expectedCheckDigit(normalizedBody).equals(normalizedCheckDigit)) {
            throw new IdentityProfileValidationException("hkid_invalid");
        }
    }

    private static String expectedCheckDigit(String body) {
        int sum = 36 * 9 + letterValue(body.charAt(0)) * 8;
        for (int i = 1; i < body.length(); i++) {
            int digit = Character.digit(body.charAt(i), 10);
            sum += digit * (8 - i);
        }
        int remainder = sum % 11;
        int check = 11 - remainder;
        if (check == 11) {
            return "0";
        }
        if (check == 10) {
            return "A";
        }
        return Integer.toString(check);
    }

    private static int letterValue(char value) {
        return value - 'A' + 10;
    }
}
