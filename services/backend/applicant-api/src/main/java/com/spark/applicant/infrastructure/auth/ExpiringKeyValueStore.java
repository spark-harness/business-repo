package com.spark.applicant.infrastructure.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

interface ExpiringKeyValueStore {
    Instant now();

    <T> Optional<T> get(String key, Class<T> type);

    void putUntil(String key, Object value, Instant expiresAt);

    default void put(String key, Object value, Duration ttl) {
        putUntil(key, value, now().plus(ttl));
    }
}
