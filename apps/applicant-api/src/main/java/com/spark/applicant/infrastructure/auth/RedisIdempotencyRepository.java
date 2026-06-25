package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.port.IdempotencyRepository;
import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
public class RedisIdempotencyRepository implements IdempotencyRepository {
    private final ExpiringKeyValueStore store;
    private final String keyPrefix;

    @Autowired
    public RedisIdempotencyRepository(ExpiringKeyValueStore store, ApplicantAuthProperties properties) {
        this(store, properties.getKeyPrefix());
    }

    RedisIdempotencyRepository(ExpiringKeyValueStore store, String keyPrefix) {
        this.store = store;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public <T> Optional<T> find(String operation, String idempotencyKey, String fingerprint, Class<T> type) {
        String baseKey = baseKey(operation, idempotencyKey);
        Optional<String> storedFingerprint = store.get(baseKey + ":fingerprint", String.class);
        if (storedFingerprint.isEmpty()) {
            return Optional.empty();
        }
        if (!storedFingerprint.get().equals(fingerprint)) {
            throw new ApplicantAuthException("idempotency_key_conflict");
        }
        return store.get(baseKey + ":result", type);
    }

    @Override
    public <T> void save(String operation, String idempotencyKey, String fingerprint, T result, Duration ttl) {
        String baseKey = baseKey(operation, idempotencyKey);
        store.put(baseKey + ":fingerprint", fingerprint, ttl);
        store.put(baseKey + ":result", result, ttl);
    }

    private String baseKey(String operation, String idempotencyKey) {
        return keyPrefix
                + ":idempotency:"
                + InfrastructureHash.normalize(operation)
                + ":"
                + InfrastructureHash.sha256(idempotencyKey);
    }

    private String normalizePrefix(String value) {
        String normalized = InfrastructureHash.normalize(value);
        return normalized.isEmpty() ? "applicant-api" : normalized;
    }
}
