package com.spark.applicant.application.auth.port;

import com.spark.applicant.domain.applicant.Applicant;
import com.spark.applicant.domain.applicant.PhoneNumber;

public interface ApplicantRepository {
    Applicant findOrCreateByPhoneNumber(PhoneNumber phoneNumber);
}
