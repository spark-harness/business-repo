package com.spark.origination.domain;

import java.time.Instant;

public record LoanApplication(
        String applicationId,
        String applicantId,
        String productCode,
        LoanTerms loan,
        AcceptedQuote acceptedQuote,
        ApplicationStatus status,
        ApplicationStep currentStep,
        Instant createdAt,
        Instant updatedAt) {
    public LoanApplication {
        if (applicationId == null || applicationId.isBlank()
                || applicantId == null || applicantId.isBlank()
                || productCode == null || productCode.isBlank()
                || loan == null
                || acceptedQuote == null
                || status == null
                || currentStep == null
                || createdAt == null
                || updatedAt == null) {
            throw new ValidationException("loan application is invalid");
        }
    }

    public LoanApplication updateLoan(LoanTerms newLoan, AcceptedQuote newQuote, Instant updatedAt) {
        return new LoanApplication(
                applicationId,
                applicantId,
                productCode,
                newLoan,
                newQuote,
                ApplicationStatus.DRAFT,
                ApplicationStep.LOAN_REQUEST,
                createdAt,
                updatedAt);
    }

    public LoanApplication advanceTo(ApplicationStep targetStep, Instant updatedAt) {
        return new LoanApplication(
                applicationId,
                applicantId,
                productCode,
                loan,
                acceptedQuote,
                ApplicationStatus.DRAFT,
                targetStep,
                createdAt,
                updatedAt);
    }
}
