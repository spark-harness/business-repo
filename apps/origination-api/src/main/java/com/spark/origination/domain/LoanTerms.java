package com.spark.origination.domain;

import java.math.BigDecimal;

public record LoanTerms(BigDecimal amount, int term, String purpose) {
    public LoanTerms {
        if (amount == null || term <= 0 || purpose == null || purpose.isBlank()) {
            throw new ValidationException("loan terms are invalid");
        }
    }
}
