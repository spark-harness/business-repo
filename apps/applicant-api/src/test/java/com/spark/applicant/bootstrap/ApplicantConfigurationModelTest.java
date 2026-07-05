package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.applicant.infrastructure.runtime.ConsulServiceRegistration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void applicationYaml_whenLoaded_shouldNotImportSpringCloudConsulConfig() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("\"optional:file:.env[.properties]\"");
        assertThat(applicationYaml).doesNotContain("\"optional:consul:\"");
        assertThat(applicationYaml).doesNotContain("spring-cloud-starter-consul-config");
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
    void applicationYaml_whenLoaded_shouldRetainCanonicalEnvPlaceholdersAndSelfRegistration() throws IOException {
        String applicationYaml = resourceText("application.yml");
        String prodYaml = resourceText("application-prod.yml");

        assertThat(applicationYaml).contains("host: ${SPRING_DATA_REDIS_HOST:localhost}");
        assertThat(applicationYaml).contains("password: ${SPRING_DATA_REDIS_PASSWORD:}");
        assertThat(applicationYaml).contains("runtime-store: ${SPARK_APPLICANT_AUTH_RUNTIME_STORE:redis-jdbc}");
        assertThat(applicationYaml).contains("otp-provider: ${SPARK_APPLICANT_AUTH_OTP_PROVIDER:test}");
        assertThat(applicationYaml).contains("token-mode: ${SPARK_APPLICANT_AUTH_TOKEN_MODE:hmac}");
        assertThat(applicationYaml).contains("jdbc-url: ${SPARK_APPLICANT_AUTH_JDBC_URL:");
        assertThat(applicationYaml).contains("jdbc-username: ${SPARK_APPLICANT_AUTH_JDBC_USERNAME:");
        assertThat(applicationYaml).contains("jdbc-password: ${SPARK_APPLICANT_AUTH_JDBC_PASSWORD:");
        assertThat(applicationYaml).contains("token-secret: ${SPARK_APPLICANT_AUTH_TOKEN_SECRET:");
        assertThat(applicationYaml).doesNotContain("forest_dev_password");
        assertThat(applicationYaml).doesNotContain("local-dev-token-secret");
        assertThat(applicationYaml).contains("url: ${SPARK_APPLICANT_AUTH_CONSUL_URL:http://localhost:8500}");
        assertThat(applicationYaml).contains("endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}");
        assertThat(applicationYaml)
                .contains("endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}}");
        assertThat(applicationYaml).contains("headers: ${OTEL_EXPORTER_OTLP_TRACES_HEADERS:${OTEL_EXPORTER_OTLP_HEADERS:}}");

        assertThat(prodYaml).contains("host: ${SPRING_DATA_REDIS_HOST:}");
        assertThat(prodYaml).contains("password: ${SPRING_DATA_REDIS_PASSWORD:}");
        assertThat(prodYaml).contains("jdbc-url: ${SPARK_APPLICANT_AUTH_JDBC_URL:}");
        assertThat(prodYaml).contains("jdbc-username: ${SPARK_APPLICANT_AUTH_JDBC_USERNAME:}");
        assertThat(prodYaml).contains("jdbc-password: ${SPARK_APPLICANT_AUTH_JDBC_PASSWORD:}");
        assertThat(prodYaml).contains("token-secret: ${SPARK_APPLICANT_AUTH_TOKEN_SECRET:}");
        assertThat(prodYaml).contains("endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}");
        assertThat(prodYaml)
                .contains("endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}}");
        assertThat(prodYaml).contains("headers: ${OTEL_EXPORTER_OTLP_TRACES_HEADERS:${OTEL_EXPORTER_OTLP_HEADERS:}}");
        assertThat(ConsulServiceRegistration.class).isNotNull();
    }

    @Test
    void pom_whenLoaded_shouldNotUseConsulConfigAndShouldKeepOpenTelemetryStarter() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).doesNotContain("<artifactId>spring-cloud-starter-consul-config</artifactId>");
        assertThat(pom).contains("<artifactId>opentelemetry-spring-boot-starter</artifactId>");
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
                .hasMessage("redis password is required in managed runtime profile")
                .hasMessageNotContaining("secret-header-value");
    }

    @Test
    void validateRuntimePolicy_whenDevRuntimeHasNoTokenSecret_shouldFailFast() {
        ApplicantAuthProperties properties = prodReadyProperties();
        properties.setTokenSecret("");
        MockEnvironment environment = prodReadyEnvironment();
        environment.setActiveProfiles("dev-1");
        environment.setProperty(
                "otel.exporter.otlp.traces.endpoint", "https://otel.example/api/1/otel/v1/traces");
        environment.setProperty("otel.exporter.otlp.traces.headers", "secret-header-value");
        environment.setProperty("spring.data.redis.password", "secret-redis-password");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ApplicantAuthConfiguration.validateRuntimePolicy(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("token secret is required for hmac token mode");
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
