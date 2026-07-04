package com.spark.origination.application;

public class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException() {}

    public QuoteExpiredException(Throwable cause) {
        super(cause);
    }
}
