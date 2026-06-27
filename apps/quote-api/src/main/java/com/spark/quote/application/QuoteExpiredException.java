package com.spark.quote.application;

public class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException(String quoteId) {
        super("quote has expired: " + quoteId);
    }
}
