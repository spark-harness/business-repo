package com.spark.origination.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record AcceptedQuote(
        String quoteId,
        String applicantId,
        BigDecimal amount,
        int term,
        String purpose,
        BigDecimal monthly,
        BigDecimal apr,
        BigDecimal totalInterest,
        BigDecimal totalPayable,
        Instant validUntil) {
    public AcceptedQuote {
        if (quoteId == null || quoteId.isBlank()
                || applicantId == null || applicantId.isBlank()
                || amount == null
                || term <= 0
                || purpose == null || purpose.isBlank()
                || monthly == null
                || apr == null
                || totalInterest == null
                || totalPayable == null
                || validUntil == null) {
            throw new ValidationException("accepted quote is invalid");
        }
    }

    public AcceptedQuote withValidUntil(Instant value) {
        return new AcceptedQuote(
                quoteId, applicantId, amount, term, purpose, monthly, apr, totalInterest, totalPayable, value);
    }
}
