package com.spark.origination.application;

public class QuoteNotFoundException extends RuntimeException {
    public QuoteNotFoundException() {}

    public QuoteNotFoundException(Throwable cause) {
        super(cause);
    }
}
