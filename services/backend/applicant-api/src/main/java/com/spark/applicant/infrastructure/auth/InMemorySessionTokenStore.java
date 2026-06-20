package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.TokenRecord;
import com.spark.applicant.application.auth.port.SessionTokenStore;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(
        prefix = "spark.applicant.auth",
        name = "runtime-store",
        havingValue = "in-memory",
        matchIfMissing = true)
public class InMemorySessionTokenStore implements SessionTokenStore {
    private final Clock clock;
    private final Map<String, TokenRecord> refreshTokens = new ConcurrentHashMap<>();

    public InMemorySessionTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void saveRefreshToken(TokenRecord tokenRecord) {
        refreshTokens.put(tokenRecord.token(), tokenRecord);
    }

    @Override
    public Optional<TokenRecord> findRefreshToken(String refreshToken, Instant now) {
        TokenRecord token = refreshTokens.get(refreshToken);
        if (token == null || !now.isBefore(token.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    public int refreshTokenCount() {
        Instant now = clock.instant();
        refreshTokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        return refreshTokens.size();
    }
}
