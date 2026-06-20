package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApplicantAuthConfigurationTest {
    @Test
    void validateRuntimePolicy_whenProdUsesTestOtpProvider_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.TEST);
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("jdbc:postgresql://localhost:5432/applicant");
        properties.setTokenSecret("test-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test OTP provider is forbidden in prod profile");
    }

    @Test
    void validateRuntimePolicy_whenHmacTokenModeHasNoSecret_shouldFailFast() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        MockEnvironment environment = new MockEnvironment();

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
        properties.setTokenSecret("test-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jdbc url is required in prod profile");
    }
}
