package com.spark.quote.application;

public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() {
        super("request principal is required");
    }
}
