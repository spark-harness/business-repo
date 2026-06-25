package com.spark.applicant.infrastructure.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
class RedisExpiringKeyValueStore implements ExpiringKeyValueStore {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    RedisExpiringKeyValueStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("redis value cannot be decoded", error);
        }
    }

    @Override
    public void putUntil(String key, Object value, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("redis value cannot be encoded", error);
        }
    }
}
