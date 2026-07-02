package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class ApplicantConfigurationModelTest {
    @Test
    void applicationYaml_whenLoaded_shouldImportOptionalConsulConfig() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("\"optional:consul:\"");
        assertThat(applicationYaml).contains("consul:\n      enabled: false");
        assertThat(applicationYaml).contains("format: yaml");
        assertThat(applicationYaml).contains("watch:");
        assertThat(applicationYaml).contains("enabled: false");
    }

    @Test
    void applicationYaml_whenLoaded_shouldAllowAnonymousAuthGrpcMethods() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("principal:\n        enabled: false");
    }

    @Test
    void applicationYaml_whenLoaded_shouldDisableActuatorDatabaseHealthProbe() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("management:\n  health:\n    db:\n      enabled: false");
    }

    @Test
    void applicationYaml_whenLoaded_shouldNotDeclareShortApplicantEnvAliases() throws IOException {
        String applicationYaml = resourceText("application.yml");
        String staYaml = resourceText("application-sta.yml");
        String prodYaml = resourceText("application-prod.yml");

        assertThat(applicationYaml + staYaml + prodYaml).doesNotContain("${APPLICANT_");
    }

    @Test
    void validateRuntimePolicy_whenProdHasNoRedisPassword_shouldFailFastWithoutSecretValue() {
        ApplicantAuthProperties properties = prodReadyProperties();
        MockEnvironment environment = prodReadyEnvironment();
        environment.setProperty(
                "otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");
        environment.setProperty("otel.exporter.otlp.traces.headers", "secret-header-value");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis password is required in prod profile")
                .hasMessageNotContaining("secret-header-value");
    }

    @Test
    void configurationSources_whenConsulAndEnvArePresent_shouldApplyExpectedPrecedence() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "application.yml",
                Map.of("spark.applicant.auth.jdbc-url", "jdbc:postgresql://default.example:5432/applicant")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "consul:applicant-api",
                Map.of("spark.applicant.auth.jdbc-url", "jdbc:postgresql://consul.example:5432/applicant")));

        ApplicantAuthProperties consulProperties = bindProperties(environment);

        assertThat(consulProperties.getJdbcUrl()).isEqualTo("jdbc:postgresql://consul.example:5432/applicant");

        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "k8s-env",
                Map.of("SPARK_APPLICANT_AUTH_JDBC_URL", "jdbc:postgresql://env.example:5432/applicant")));

        ApplicantAuthProperties envProperties = bindProperties(environment);

        assertThat(envProperties.getJdbcUrl()).isEqualTo("jdbc:postgresql://env.example:5432/applicant");
    }

    private static String resourceText(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static ApplicantAuthProperties bindProperties(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("spark.applicant.auth", ApplicantAuthProperties.class)
                .orElseThrow(() -> new IllegalStateException("failed to bind applicant auth properties"));
    }

    private ApplicantAuthProperties prodReadyProperties() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.setRuntimeStore(ApplicantAuthProperties.RuntimeStore.REDIS_JDBC);
        properties.setOtpProvider(ApplicantAuthProperties.OtpProvider.DISABLED);
        properties.setTokenMode(ApplicantAuthProperties.TokenMode.HMAC);
        properties.setJdbcUrl("jdbc:postgresql://postgres.example:5432/applicant");
        properties.setJdbcUsername("applicant");
        properties.setJdbcPassword("secret-db-password");
        properties.setTokenSecret("secret-token-value");
        properties.getConsul().setUrl("http://consul.example:8500");
        properties.getConsul().setServiceAddress("applicant-api.example");
        return properties;
    }

    private MockEnvironment prodReadyEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.data.redis.host", "redis.example");
        return environment;
    }
}
