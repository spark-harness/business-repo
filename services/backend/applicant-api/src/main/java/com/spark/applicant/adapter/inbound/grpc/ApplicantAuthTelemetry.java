package com.spark.applicant.adapter.inbound.grpc;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ApplicantAuthTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicantAuthTelemetry.class);

    private final MeterRegistry meterRegistry;

    ApplicantAuthTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    <T> T record(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = action.get();
            count(operation, "success", "none");
            stop(sample, operation, "success", "none");
            SpanContext spanContext = Span.current().getSpanContext();
            LOGGER.info(
                    "service=applicant-api operation={} result=success trace_id={} span_id={}",
                    operation,
                    spanContext.getTraceId(),
                    spanContext.getSpanId());
            return result;
        } catch (RuntimeException error) {
            String errorCode = error.getMessage() == null || error.getMessage().isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            count(operation, "failure", errorCode);
            stop(sample, operation, "failure", errorCode);
            Span.current().setStatus(StatusCode.ERROR);
            Span.current().setAttribute("error_code", errorCode);
            SpanContext spanContext = Span.current().getSpanContext();
            LOGGER.warn(
                    "service=applicant-api operation={} result=failure error_code={} trace_id={} span_id={}",
                    operation,
                    errorCode,
                    spanContext.getTraceId(),
                    spanContext.getSpanId());
            throw error;
        }
    }

    private void count(String operation, String result, String errorCode) {
        meterRegistry.counter(
                        "applicant.auth.requests",
                        "operation",
                        operation,
                        "result",
                        result,
                        "error_code",
                        errorCode)
                .increment();
    }

    private void stop(Timer.Sample sample, String operation, String result, String errorCode) {
        sample.stop(meterRegistry.timer(
                "applicant.auth.duration",
                "operation",
                operation,
                "result",
                result,
                "error_code",
                errorCode));
    }
}
