package com.spark.user.application.auth.port;

public interface VerificationCodeVerifier {
    boolean verify(String mobile, String verificationCode);
}
