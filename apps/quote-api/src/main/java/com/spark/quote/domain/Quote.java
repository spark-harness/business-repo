package com.spark.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Quote(
        String quoteId,
        String applicantId,
        String productCode,
        BigDecimal amount,
        int termMonths,
        String purpose,
        BigDecimal monthly,
        BigDecimal apr,
        BigDecimal totalInterest,
        BigDecimal totalPayable,
        Instant validUntil,
        Instant createdAt) {}
