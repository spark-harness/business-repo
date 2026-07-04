package com.spark.origination.application;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {}

    public ForbiddenException(Throwable cause) {
        super(cause);
    }
}
