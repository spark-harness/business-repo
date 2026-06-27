package com.spark.origination.domain;

public enum ApplicationStatus {
    DRAFT("draft");

    private final String value;

    ApplicationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
