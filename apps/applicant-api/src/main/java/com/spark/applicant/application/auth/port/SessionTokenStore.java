package com.spark.applicant.application.auth.port;

import com.spark.applicant.application.auth.TokenRecord;
import java.time.Instant;
import java.util.Optional;

public interface SessionTokenStore {
    void saveRefreshToken(TokenRecord tokenRecord);

    Optional<TokenRecord> findRefreshToken(String refreshToken, Instant now);
}
