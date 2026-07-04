package com.spark.origination.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.infrastructure.ConsulServiceRegistration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class OriginationConfigurationModelTest {
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
    void applicationYaml_whenLoaded_shouldRetainCanonicalEnvPlaceholdersSelfRegistrationAndGrpcQuoteConfig()
            throws IOException {
        String applicationYaml = resourceText("application.yml");

        assertThat(applicationYaml).contains("jdbc-url: ${ORIGINATION_JDBC_URL:");
        assertThat(applicationYaml).contains("quote-api-grpc-target: ${ORIGINATION_QUOTE_API_GRPC_TARGET:");
        assertThat(applicationYaml).contains("quote-api-grpc-timeout: ${ORIGINATION_QUOTE_API_GRPC_TIMEOUT:");
        assertThat(applicationYaml).doesNotContain("ORIGINATION_QUOTE_API_BASE_URL");
        assertThat(applicationYaml).doesNotContain("quote-api-base-url");
        assertThat(applicationYaml).contains("enabled: ${SPARK_ORIGINATION_CONSUL_ENABLED:false}");
        assertThat(applicationYaml).contains("url: ${SPARK_ORIGINATION_CONSUL_URL:http://localhost:8500}");
        assertThat(applicationYaml).contains("service-address: ${SPARK_ORIGINATION_CONSUL_SERVICE_ADDRESS:127.0.0.1}");
        assertThat(applicationYaml).contains("grpc-port: ${SPARK_ORIGINATION_CONSUL_GRPC_PORT:9090}");
        assertThat(applicationYaml).contains("port: ${SPARK_GRPC_SERVER_PORT:9090}");
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
                Map.of(
                        "spark.origination.quote-api-grpc-target",
                        "quote.default.example:9090")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "consul:origination-api",
                Map.of(
                        "spark.origination.quote-api-grpc-target",
                        "quote.consul.example:9090")));

        OriginationProperties consulProperties = bindProperties(environment);

        assertThat(consulProperties.getQuoteApiGrpcTarget()).isEqualTo("quote.consul.example:9090");
    }

    private static String resourceText(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static OriginationProperties bindProperties(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("spark.origination", OriginationProperties.class)
                .orElseThrow(() -> new IllegalStateException("failed to bind origination properties"));
    }
}
