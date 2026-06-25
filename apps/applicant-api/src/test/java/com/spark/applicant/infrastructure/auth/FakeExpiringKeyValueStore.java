package com.spark.applicant.infrastructure.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class FakeExpiringKeyValueStore implements ExpiringKeyValueStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private Instant now;

    FakeExpiringKeyValueStore(Instant now) {
        this.now = now;
    }

    @Override
    public Instant now() {
        return now;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null || !now.isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(type.cast(entry.value()));
    }

    @Override
    public void putUntil(String key, Object value, Instant expiresAt) {
        entries.put(key, new Entry(value, expiresAt));
    }

    private record Entry(Object value, Instant expiresAt) {}
}
