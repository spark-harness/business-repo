package com.spark.origination.application;

import com.spark.origination.domain.ApplicationStatus;
import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class CreateLoanApplicationUseCase {
    private final LoanApplicationRepository applications;
    private final IdempotencyRepository idempotency;
    private final QuoteGateway quoteGateway;
    private final Clock clock;

    public CreateLoanApplicationUseCase(
            LoanApplicationRepository applications,
            IdempotencyRepository idempotency,
            QuoteGateway quoteGateway,
            Clock clock) {
        this.applications = applications;
        this.idempotency = idempotency;
        this.quoteGateway = quoteGateway;
        this.clock = clock;
    }

    public LoanApplication create(CreateLoanApplicationCommand command) {
        LoanApplicationRules.validateCreate(command);
        LoanApplicationRules.requireIdempotencyKey(command.idempotencyKey());
        String applicantId = LoanApplicationRules.currentApplicantId();
        String requestHash = LoanApplicationRules.createRequestHash(command);
        return idempotency.findApplicationId(applicantId, "create", command.idempotencyKey(), requestHash)
                .flatMap(applications::findById)
                .orElseGet(() -> createNew(command, applicantId, requestHash));
    }

    private LoanApplication createNew(CreateLoanApplicationCommand command, String applicantId, String requestHash) {
        var quote = quoteGateway.get(command.quoteId());
        LoanApplicationRules.requireUsableQuote(applicantId, command.loan(), quote, clock);
        Instant now = clock.instant();
        LoanApplication application = new LoanApplication(
                "app_" + UUID.randomUUID(),
                applicantId,
                command.productCode(),
                command.loan(),
                quote,
                ApplicationStatus.DRAFT,
                ApplicationStep.LOAN_REQUEST,
                now,
                now);
        applications.save(application);
        idempotency.save(applicantId, "create", command.idempotencyKey(), requestHash, application.applicationId());
        return application;
    }
}
