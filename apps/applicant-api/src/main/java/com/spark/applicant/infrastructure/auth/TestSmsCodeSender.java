package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.SmsCodeSender;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(
        prefix = "spark.applicant.auth",
        name = "otp-provider",
        havingValue = "test",
        matchIfMissing = true)
public class TestSmsCodeSender implements SmsCodeSender {
    private final String code;

    public TestSmsCodeSender() {
        this("123456");
    }

    public TestSmsCodeSender(String code) {
        this.code = code;
    }

    @Override
    public String sendCode(PhoneNumber phoneNumber) {
        return code;
    }
}
