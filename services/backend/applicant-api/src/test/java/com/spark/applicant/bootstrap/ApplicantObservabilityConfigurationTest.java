package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=in-memory",
            "spark.applicant.auth.token-mode=simple",
            "spark.applicant.auth.consul.enabled=false",
            "otel.traces.exporter=otlp",
            "otel.exporter.otlp.traces.endpoint=https://otel.example/api/1/otel/v1/traces",
            "otel.exporter.otlp.traces.headers=x-sentry-auth=sentry sentry_key=public"
        })
class ApplicantObservabilityConfigurationTest {
    @Autowired
    private Environment environment;

    @Autowired
    private ObjectProvider<OpenTelemetry> openTelemetry;

    @Test
    void applicationContext_whenOtlpIsConfigured_shouldExportOpenTelemetryTraces() {
        assertThat(openTelemetry.getIfAvailable()).isNotNull();
        assertThat(environment.getProperty("otel.propagators")).isEqualTo("tracecontext,baggage");
        assertThat(environment.getProperty("otel.traces.exporter")).isEqualTo("otlp");
        assertThat(environment.getProperty("otel.exporter.otlp.traces.protocol")).isEqualTo("http/protobuf");
        assertThat(environment.getProperty("otel.logs.exporter")).isEqualTo("none");
        assertThat(environment.getProperty("otel.metrics.exporter")).isEqualTo("none");
        assertThat(environment.getProperty("otel.service.name")).isEqualTo("applicant-api");
    }
}
