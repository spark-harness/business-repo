package com.spark.origination.application;

public class QuoteUnavailableException extends RuntimeException {
    public QuoteUnavailableException() {}

    public QuoteUnavailableException(String message) {
        super(message);
    }

    public QuoteUnavailableException(Throwable cause) {
        super(cause);
    }
}
