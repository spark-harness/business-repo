package com.spark.quote.application;

public class QuoteNotFoundException extends RuntimeException {
    public QuoteNotFoundException(String quoteId) {
        super("quote not found: " + quoteId);
    }
}
