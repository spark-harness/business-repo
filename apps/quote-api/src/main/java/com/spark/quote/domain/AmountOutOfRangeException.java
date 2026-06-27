package com.spark.quote.domain;

public class AmountOutOfRangeException extends RuntimeException {
    public AmountOutOfRangeException(String message) {
        super(message);
    }
}
