package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApplicantAuthConfigurationTest {
    @Test
    void defaultProperties_whenConstructed_shouldUseRealLocalRuntime() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();

        assertThat(properties.getRuntimeStore()).isEqualTo(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        assertThat(properties.getOtpProvider()).isEqualTo(ApplicantAuthProperties.OtpProvider.TEST);
        assertThat(properties.getTokenMode()).isEqualTo(ApplicantAuthProperties.TokenMode.HMAC);
    }

    @Test
    void validateRuntimePolicy_whenProdUsesTestOtpProvider_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.TEST);
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("jdbc:postgresql://localhost:5432/applicant");
        properties.setJdbcPassword("test-db-secret");
        properties.setTokenSecret("test-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.data.redis.host", "localhost");
        environment.setProperty("spring.data.redis.password", "test-redis-secret");
        environment.setProperty("otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");
        environment.setProperty("otel.exporter.otlp.traces.headers", "secret-header-value");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test OTP provider is forbidden in prod profile");
    }

    @Test
    void validateRuntimePolicy_whenHmacTokenModeHasNoSecret_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setTokenSecret("");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev-1");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("token secret is required for hmac token mode");
    }

    @Test
    void validateRuntimePolicy_whenProdHasNoJdbcUrl_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.DISABLED);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("");
        properties.setJdbcPassword("test-db-secret");
        properties.setTokenSecret("test-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.data.redis.host", "localhost");
        environment.setProperty("spring.data.redis.password", "test-redis-secret");
        environment.setProperty("otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");
        environment.setProperty("otel.exporter.otlp.traces.headers", "secret-header-value");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jdbc url is required in managed runtime profile");
    }

    @Test
    void validateRuntimePolicy_whenProdHasNoRedisHost_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.DISABLED);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("jdbc:postgresql://localhost:5432/applicant");
        properties.setJdbcPassword("test-db-secret");
        properties.setTokenSecret("test-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.data.redis.password", "test-redis-secret");
        environment.setProperty("otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");
        environment.setProperty("otel.exporter.otlp.traces.headers", "secret-header-value");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis host is required in managed runtime profile");
    }

    @Test
    void validateRuntimePolicy_whenProdHasNoOtlpEndpoint_shouldFailFast() {
        ApplicantAuthProperties properties = prodReadyProperties();
        MockEnvironment environment = prodReadyEnvironment();

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("otlp traces endpoint is required in managed runtime profile");
    }

    @Test
    void validateRuntimePolicy_whenProdHasNoOtlpHeaders_shouldFailFast() {
        ApplicantAuthProperties properties = prodReadyProperties();
        MockEnvironment environment = prodReadyEnvironment();
        environment.setProperty(
                "otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("otlp traces headers are required in managed runtime profile");
    }

    private ApplicantAuthProperties prodReadyProperties() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.DISABLED);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("jdbc:postgresql://localhost:5432/applicant");
        properties.setJdbcPassword("test-db-secret");
        properties.setTokenSecret("test-secret");
        properties.getConsul().setUrl("http://localhost:8500");
        properties.getConsul().setServiceAddress("127.0.0.1");
        return properties;
    }

    private MockEnvironment prodReadyEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.data.redis.host", "localhost");
        environment.setProperty("spring.data.redis.password", "test-redis-secret");
        return environment;
    }
}
