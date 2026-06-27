package com.spark.origination.domain;

public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
