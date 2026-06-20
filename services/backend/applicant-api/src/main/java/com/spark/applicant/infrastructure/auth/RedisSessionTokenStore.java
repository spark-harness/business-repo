package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.TokenRecord;
import com.spark.applicant.application.auth.port.SessionTokenStore;
import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
public class RedisSessionTokenStore implements SessionTokenStore {
    private final ExpiringKeyValueStore store;
    private final String keyPrefix;

    public RedisSessionTokenStore(ExpiringKeyValueStore store, ApplicantAuthProperties properties) {
        this(store, properties.getKeyPrefix());
    }

    RedisSessionTokenStore(ExpiringKeyValueStore store, String keyPrefix) {
        this.store = store;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public void saveRefreshToken(TokenRecord tokenRecord) {
        store.putUntil(refreshTokenKey(tokenRecord.token()), tokenRecord, tokenRecord.expiresAt());
    }

    @Override
    public Optional<TokenRecord> findRefreshToken(String refreshToken, Instant now) {
        return store.get(refreshTokenKey(refreshToken), TokenRecord.class)
                .filter(token -> now.isBefore(token.expiresAt()));
    }

    private String refreshTokenKey(String refreshToken) {
        return keyPrefix + ":token:refresh:" + InfrastructureHash.sha256(refreshToken);
    }

    private String normalizePrefix(String value) {
        String normalized = InfrastructureHash.normalize(value);
        return normalized.isEmpty() ? "applicant-api" : normalized;
    }
}
