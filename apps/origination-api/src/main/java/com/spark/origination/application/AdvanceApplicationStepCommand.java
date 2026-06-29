package com.spark.origination.application;

import com.spark.origination.domain.ApplicationStep;

public record AdvanceApplicationStepCommand(String applicantId, String applicationId, ApplicationStep targetStep) {
    public AdvanceApplicationStepCommand(String applicationId, ApplicationStep targetStep) {
        this(null, applicationId, targetStep);
    }
}
