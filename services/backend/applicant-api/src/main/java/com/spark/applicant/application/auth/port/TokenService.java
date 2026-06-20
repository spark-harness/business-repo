package com.spark.applicant.application.auth.port;

public interface TokenService {
    String issueAccessToken(String applicantId);

    String issueRefreshToken(String applicantId);
}
