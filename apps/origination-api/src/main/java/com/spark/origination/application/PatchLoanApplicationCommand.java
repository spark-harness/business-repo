package com.spark.origination.application;

import com.spark.origination.domain.LoanTerms;

public record PatchLoanApplicationCommand(
        String applicationId, LoanTerms loan, String quoteId, String idempotencyKey) {}
