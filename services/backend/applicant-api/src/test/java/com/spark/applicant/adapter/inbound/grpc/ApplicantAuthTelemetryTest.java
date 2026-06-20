package com.spark.applicant.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.applicant.application.auth.ApplicantAuthException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ApplicantAuthTelemetryTest {
    @Test
    void record_whenOperationSucceeds_shouldIncrementSuccessMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicantAuthTelemetry telemetry = new ApplicantAuthTelemetry(meterRegistry);

        String result = telemetry.record("send_otp", () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(meterRegistry
                        .counter(
                                "applicant.auth.requests",
                                "operation",
                                "send_otp",
                                "result",
                                "success",
                                "error_code",
                                "none")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void record_whenOperationFails_shouldIncrementFailureMetricWithStableErrorCode() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicantAuthTelemetry telemetry = new ApplicantAuthTelemetry(meterRegistry);

        assertThatThrownBy(() -> telemetry.record("verify_otp", () -> {
                    throw new ApplicantAuthException("otp_code_invalid");
                }))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("otp_code_invalid");

        assertThat(meterRegistry
                        .counter(
                                "applicant.auth.requests",
                                "operation",
                                "verify_otp",
                                "result",
                                "failure",
                                "error_code",
                                "otp_code_invalid")
                        .count())
                .isEqualTo(1);
    }
}
