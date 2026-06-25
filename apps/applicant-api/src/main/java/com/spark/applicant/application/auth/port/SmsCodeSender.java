package com.spark.applicant.application.auth.port;

import com.spark.applicant.domain.applicant.PhoneNumber;

public interface SmsCodeSender {
    String sendCode(PhoneNumber phoneNumber);
}
