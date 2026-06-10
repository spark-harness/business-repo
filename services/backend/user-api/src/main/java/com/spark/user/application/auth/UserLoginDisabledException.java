package com.spark.user.application.auth;

public class UserLoginDisabledException extends RuntimeException {
    public UserLoginDisabledException(String message) {
        super(message);
    }
}
