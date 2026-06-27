package com.spark.origination.application;

import com.spark.origination.domain.LoanApplication;

public class GetLoanApplicationUseCase {
    private final LoanApplicationRepository applications;

    public GetLoanApplicationUseCase(LoanApplicationRepository applications) {
        this.applications = applications;
    }

    public LoanApplication get(String applicationId) {
        String applicantId = LoanApplicationRules.currentApplicantId();
        LoanApplication application = applications.findById(applicationId)
                .orElseThrow(ApplicationNotFoundException::new);
        LoanApplicationRules.requireOwner(applicantId, application);
        return application;
    }
}
