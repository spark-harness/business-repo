package com.spark.applicant.bootstrap;

import com.spark.applicant.application.auth.AuthPolicy;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(ApplicantAuthProperties.class)
public class ApplicantAuthConfiguration {
    @Bean
    AuthPolicy authPolicy(ApplicantAuthProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
    DataSource applicantDataSource(ApplicantAuthProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    @Bean
    @ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
    JdbcTemplate applicantJdbcTemplate(DataSource applicantDataSource) {
        return new JdbcTemplate(applicantDataSource);
    }

    @Bean
    ApplicationRunner applicantAuthRuntimePolicyValidator(
            ApplicantAuthProperties properties, Environment environment) {
        return ignored -> validateRuntimePolicy(properties, environment);
    }

    static void validateRuntimePolicy(ApplicantAuthProperties properties, Environment environment) {
        if (properties.getTokenMode() == ApplicantAuthProperties.TokenMode.HMAC
                && (properties.getTokenSecret() == null || properties.getTokenSecret().isBlank())) {
            throw new IllegalStateException("token secret is required for hmac token mode");
        }
        if (!isProd(environment)) {
            return;
        }
        if (properties.getOtpProvider() == ApplicantAuthProperties.OtpProvider.TEST) {
            throw new IllegalStateException("test OTP provider is forbidden in prod profile");
        }
        if (properties.getRuntimeStore() != ApplicantAuthProperties.RuntimeStore.REDIS_JDBC) {
            throw new IllegalStateException("redis-jdbc runtime store is required in prod profile");
        }
        if (properties.getJdbcUrl() == null || properties.getJdbcUrl().isBlank()) {
            throw new IllegalStateException("jdbc url is required in prod profile");
        }
        if (properties.getTokenMode() != ApplicantAuthProperties.TokenMode.HMAC) {
            throw new IllegalStateException("hmac token mode is required in prod profile");
        }
    }

    private static boolean isProd(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
