package com.spark.quote.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.infrastructure.ConsulServiceRegistration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class QuoteConfigurationModelTest {
    @Test
    void applicationYaml_whenLoaded_shouldNotImportSpringCloudConsulConfig() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("\"optional:file:.env[.properties]\"");
        assertThat(applicationYaml).doesNotContain("\"optional:consul:\"");
        assertThat(applicationYaml).doesNotContain("spring-cloud-starter-consul-config");
    }

    @Test
    void applicationYaml_whenLoaded_shouldExposeStandardOtlpTraceConfig() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("propagators: tracecontext,baggage");
        assertThat(applicationYaml).contains("exporter: ${OTEL_TRACES_EXPORTER:none}");
        assertThat(applicationYaml).contains("sampler: ${OTEL_TRACES_SAMPLER:parentbased_traceidratio}");
        assertThat(applicationYaml).contains("sampler.arg: ${OTEL_TRACES_SAMPLER_ARG:0.0}");
        assertThat(applicationYaml).contains("protocol: http/protobuf");
        assertThat(applicationYaml).contains("endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:}");
        assertThat(applicationYaml).contains("headers: ${OTEL_EXPORTER_OTLP_TRACES_HEADERS:}");
    }

    @Test
    void applicationYaml_whenLoaded_shouldDisableActuatorDatabaseHealthProbe() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("management:\n  health:\n    db:\n      enabled: false");
    }

    @Test
    void applicationYaml_whenLoaded_shouldRetainCanonicalEnvPlaceholdersAndSelfRegistration() throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("jdbc-url: ${QUOTE_JDBC_URL:");
        assertThat(applicationYaml).contains("jdbc-username: ${QUOTE_JDBC_USERNAME:sa}");
        assertThat(applicationYaml).contains("jdbc-password: ${QUOTE_JDBC_PASSWORD:}");
        assertThat(applicationYaml).contains("enabled: ${SPARK_QUOTE_CONSUL_ENABLED:false}");
        assertThat(applicationYaml).contains("url: ${SPARK_QUOTE_CONSUL_URL:http://localhost:8500}");
        assertThat(applicationYaml).contains("service-address: ${SPARK_QUOTE_CONSUL_SERVICE_ADDRESS:127.0.0.1}");
        assertThat(ConsulServiceRegistration.class).isNotNull();
    }

    @Test
    void pom_whenLoaded_shouldNotUseConsulConfigAndShouldKeepOpenTelemetryStarter() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).doesNotContain("<artifactId>spring-cloud-starter-consul-config</artifactId>");
        assertThat(pom).contains("<artifactId>opentelemetry-spring-boot-starter</artifactId>");
    }

    @Test
    void configurationSources_whenConsulAndEnvArePresent_shouldApplyExpectedPrecedence() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "application.yml",
                Map.of("spark.quote.jdbc-url", "jdbc:postgresql://default.example:5432/quote")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "consul:quote-api",
                Map.of("spark.quote.jdbc-url", "jdbc:postgresql://consul.example:5432/quote")));

        QuoteProperties consulProperties = bindProperties(environment);

        assertThat(consulProperties.getJdbcUrl()).isEqualTo("jdbc:postgresql://consul.example:5432/quote");
    }

    private static String resourceText(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static QuoteProperties bindProperties(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("spark.quote", QuoteProperties.class)
                .orElseThrow(() -> new IllegalStateException("failed to bind quote properties"));
    }
}
