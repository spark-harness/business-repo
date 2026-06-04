package com.spark.user.infrastructure.auth;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.user.application.auth.port.VerificationCodeVerifier;

@InfrastructureAdapter
public class FixedVerificationCodeVerifier implements VerificationCodeVerifier {
    private static final String ACCEPTED_CODE = "123456";

    @Override
    public boolean verify(String mobile, String verificationCode) {
        return ACCEPTED_CODE.equals(verificationCode);
    }
}
