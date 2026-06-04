package com.spark.user.application.auth;

public class InvalidAuthRequestException extends RuntimeException {
    public InvalidAuthRequestException(String message) {
        super(message);
    }
}
