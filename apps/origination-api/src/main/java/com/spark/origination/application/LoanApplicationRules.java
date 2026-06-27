package com.spark.origination.application;

import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import com.spark.origination.domain.ValidationException;
import java.time.Clock;
import java.util.Objects;

final class LoanApplicationRules {
    private LoanApplicationRules() {}

    static String currentApplicantId() {
        return RequestPrincipalContext.current()
                .orElseThrow(UnauthorizedException::new)
                .applicantId();
    }

    static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
    }

    static void validateCreate(CreateLoanApplicationCommand command) {
        if (command == null
                || command.productCode() == null
                || command.productCode().isBlank()
                || command.loan() == null
                || command.quoteId() == null
                || command.quoteId().isBlank()) {
            throw new ValidationException("loan application request is invalid");
        }
    }

    static void validatePatch(PatchLoanApplicationCommand command) {
        if (command == null
                || command.applicationId() == null
                || command.applicationId().isBlank()
                || command.loan() == null
                || command.quoteId() == null
                || command.quoteId().isBlank()) {
            throw new ValidationException("loan application request is invalid");
        }
    }

    static void requireOwner(String applicantId, LoanApplication application) {
        if (!Objects.equals(applicantId, application.applicantId())) {
            throw new ForbiddenException();
        }
    }

    static void requireUsableQuote(String applicantId, LoanTerms loan, AcceptedQuote quote, Clock clock) {
        if (!Objects.equals(applicantId, quote.applicantId())) {
            throw new ForbiddenException();
        }
        if (!clock.instant().isBefore(quote.validUntil())) {
            throw new QuoteExpiredException();
        }
        if (loan.amount().compareTo(quote.amount()) != 0
                || loan.term() != quote.term()
                || !Objects.equals(loan.purpose(), quote.purpose())) {
            throw new AmountOutOfRangeException();
        }
    }

    static String createRequestHash(CreateLoanApplicationCommand command) {
        return command.productCode() + "|" + command.loan().amount().toPlainString() + "|"
                + command.loan().term() + "|" + command.loan().purpose() + "|" + command.quoteId();
    }

    static String patchRequestHash(PatchLoanApplicationCommand command) {
        return command.applicationId() + "|" + command.loan().amount().toPlainString() + "|"
                + command.loan().term() + "|" + command.loan().purpose() + "|" + command.quoteId();
    }
}
