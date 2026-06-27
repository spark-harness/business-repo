package com.spark.origination.application;

import com.spark.origination.domain.LoanApplication;
import java.time.Clock;

public class PatchLoanApplicationUseCase {
    private final LoanApplicationRepository applications;
    private final IdempotencyRepository idempotency;
    private final QuoteGateway quoteGateway;
    private final Clock clock;

    public PatchLoanApplicationUseCase(
            LoanApplicationRepository applications,
            IdempotencyRepository idempotency,
            QuoteGateway quoteGateway,
            Clock clock) {
        this.applications = applications;
        this.idempotency = idempotency;
        this.quoteGateway = quoteGateway;
        this.clock = clock;
    }

    public LoanApplication patch(PatchLoanApplicationCommand command) {
        LoanApplicationRules.validatePatch(command);
        LoanApplicationRules.requireIdempotencyKey(command.idempotencyKey());
        String applicantId = LoanApplicationRules.currentApplicantId();
        String requestHash = LoanApplicationRules.patchRequestHash(command);
        return idempotency.findApplicationId(applicantId, "patch", command.idempotencyKey(), requestHash)
                .flatMap(applications::findById)
                .orElseGet(() -> patchExisting(command, applicantId, requestHash));
    }

    private LoanApplication patchExisting(PatchLoanApplicationCommand command, String applicantId, String requestHash) {
        LoanApplication current = applications.findById(command.applicationId())
                .orElseThrow(ApplicationNotFoundException::new);
        LoanApplicationRules.requireOwner(applicantId, current);
        var quote = quoteGateway.get(command.quoteId());
        LoanApplicationRules.requireUsableQuote(applicantId, command.loan(), quote, clock);
        LoanApplication updated = current.updateLoan(command.loan(), quote, clock.instant());
        applications.save(updated);
        idempotency.save(applicantId, "patch", command.idempotencyKey(), requestHash, updated.applicationId());
        return updated;
    }
}
