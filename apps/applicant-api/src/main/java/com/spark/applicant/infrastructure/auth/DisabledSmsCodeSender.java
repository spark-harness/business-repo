package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.port.SmsCodeSender;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "otp-provider", havingValue = "disabled")
public class DisabledSmsCodeSender implements SmsCodeSender {
    @Override
    public String sendCode(PhoneNumber phoneNumber) {
        throw new ApplicantAuthException("otp_provider_disabled");
    }
}
