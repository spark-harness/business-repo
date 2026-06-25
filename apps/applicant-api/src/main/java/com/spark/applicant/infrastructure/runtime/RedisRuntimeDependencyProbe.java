package com.spark.applicant.infrastructure.runtime;

import com.spark.applicant.application.runtime.RuntimeDependencyProbe;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
class RedisRuntimeDependencyProbe implements RuntimeDependencyProbe {
    private final StringRedisTemplate redisTemplate;

    RedisRuntimeDependencyProbe(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Status check() {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            return Status.down("redis");
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong) ? Status.up("redis") : Status.down("redis");
        } catch (RuntimeException error) {
            return Status.down("redis");
        }
    }
}
