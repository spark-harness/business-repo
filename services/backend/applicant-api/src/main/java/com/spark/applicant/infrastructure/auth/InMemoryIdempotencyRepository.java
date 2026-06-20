package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.port.IdempotencyRepository;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.time.Duration;
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
public class InMemoryIdempotencyRepository implements IdempotencyRepository {
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryIdempotencyRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public <T> Optional<T> find(String operation, String idempotencyKey, String fingerprint, Class<T> type) {
        Entry entry = entries.get(key(operation, idempotencyKey));
        if (entry == null || !clock.instant().isBefore(entry.expiresAt())) {
            return Optional.empty();
        }
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new ApplicantAuthException("idempotency_key_conflict");
        }
        return Optional.of(type.cast(entry.result()));
    }

    @Override
    public <T> void save(String operation, String idempotencyKey, String fingerprint, T result, Duration ttl) {
        entries.put(key(operation, idempotencyKey), new Entry(fingerprint, result, clock.instant().plus(ttl)));
    }

    private String key(String operation, String idempotencyKey) {
        return operation + ":" + idempotencyKey;
    }

    private record Entry(String fingerprint, Object result, Instant expiresAt) {}
}
