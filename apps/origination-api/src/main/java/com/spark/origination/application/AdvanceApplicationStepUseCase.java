package com.spark.origination.application;

import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import java.time.Clock;

public class AdvanceApplicationStepUseCase {
    private final LoanApplicationRepository applications;
    private final Clock clock;

    public AdvanceApplicationStepUseCase(LoanApplicationRepository applications, Clock clock) {
        this.applications = applications;
        this.clock = clock;
    }

    public LoanApplication advance(AdvanceApplicationStepCommand command) {
        if (command == null || command.applicationId() == null || command.applicationId().isBlank()) {
            throw new ApplicationRequiredException();
        }
        if (command.targetStep() != ApplicationStep.IDENTITY_INFORMATION) {
            throw new InvalidStepException();
        }
        String applicantId = command.applicantId() == null || command.applicantId().isBlank()
                ? LoanApplicationRules.currentApplicantId()
                : command.applicantId();
        LoanApplication application = applications.findById(command.applicationId())
                .orElseThrow(ApplicationNotFoundException::new);
        LoanApplicationRules.requireOwner(applicantId, application);
        LoanApplication advanced = application.advanceTo(command.targetStep(), clock.instant());
        applications.save(advanced);
        return advanced;
    }
}
