package com.spark.applicant.application.auth;

public class ApplicantAuthException extends RuntimeException {
    public ApplicantAuthException(String errorCode) {
        super(errorCode);
    }
}
