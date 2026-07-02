package com.spark.origination.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

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
    void pom_whenLoaded_shouldUseConsulConfigAndOpenTelemetryStarter() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-consul-config</artifactId>");
        assertThat(pom).contains("<artifactId>opentelemetry-spring-boot-starter</artifactId>");
    }

    @Test
    void configurationSources_whenConsulAndEnvArePresent_shouldApplyExpectedPrecedence() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "application.yml",
                Map.of(
                        "spark.origination.quote-api-base-url",
                        "http://quote.default.example")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "consul:origination-api",
                Map.of(
                        "spark.origination.quote-api-base-url",
                        "http://quote.consul.example")));

        OriginationProperties consulProperties = bindProperties(environment);

        assertThat(consulProperties.getQuoteApiBaseUrl()).isEqualTo("http://quote.consul.example");
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
