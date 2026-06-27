package com.spark.quote.application;

public class QuoteForbiddenException extends RuntimeException {
    public QuoteForbiddenException() {
        super("quote does not belong to applicant");
    }
}
