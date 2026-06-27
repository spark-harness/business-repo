package com.spark.origination.domain;

public enum ApplicationStep {
    LOAN_REQUEST("loan_request");

    private final String value;

    ApplicationStep(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
