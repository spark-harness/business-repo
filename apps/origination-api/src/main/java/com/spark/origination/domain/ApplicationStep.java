package com.spark.origination.domain;

public enum ApplicationStep {
    LOAN_REQUEST("loan_request"),
    IDENTITY_INFORMATION("identity_information");

    private final String value;

    ApplicationStep(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ApplicationStep fromValue(String value) {
        for (ApplicationStep step : values()) {
            if (step.value.equals(value)) {
                return step;
            }
        }
        throw new ValidationException("invalid application step");
    }
}
