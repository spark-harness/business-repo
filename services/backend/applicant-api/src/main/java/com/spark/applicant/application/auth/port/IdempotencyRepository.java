package com.spark.applicant.application.auth.port;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyRepository {
    <T> Optional<T> find(String operation, String idempotencyKey, String fingerprint, Class<T> type);

    <T> void save(String operation, String idempotencyKey, String fingerprint, T result, Duration ttl);
}
