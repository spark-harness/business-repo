package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.TokenService;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(
        prefix = "spark.applicant.auth",
        name = "token-mode",
        havingValue = "simple",
        matchIfMissing = true)
public class SimpleTokenService implements TokenService {
    private final Clock clock;

    public SimpleTokenService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String issueAccessToken(String applicantId) {
        return "access_" + applicantId + "_" + clock.millis() + "_" + UUID.randomUUID();
    }

    @Override
    public String issueRefreshToken(String applicantId) {
        return "refresh_" + applicantId + "_" + clock.millis() + "_" + UUID.randomUUID();
    }
}
