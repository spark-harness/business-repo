package com.spark.origination.application;

import com.spark.origination.domain.LoanTerms;

public record CreateLoanApplicationCommand(
        String productCode, LoanTerms loan, String quoteId, String idempotencyKey) {}
