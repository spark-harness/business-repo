package com.spark.user.application.profile;

public class InvalidProfileRequestException extends RuntimeException {
    public InvalidProfileRequestException(String message) {
        super(message);
    }
}
