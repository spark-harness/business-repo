package com.spark.common.spring.cleanarchitecture.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;

public class OpenTelemetryGrpcServerInterceptor implements ServerInterceptor, Ordered {
    public static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Pattern STABLE_ERROR_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OpenTelemetryGrpcServerInterceptor(OpenTelemetry openTelemetry) {
        this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.tracer = openTelemetry.getTracer("com.spark.common.spring.grpc-server");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Context parentContext = openTelemetry
                .getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), headers, MetadataGetter.INSTANCE);
        Span span = tracer.spanBuilder(call.getMethodDescriptor().getFullMethodName())
                .setParent(parentContext)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.method", call.getMethodDescriptor().getBareMethodName())
                .setAttribute("rpc.service", call.getMethodDescriptor().getServiceName())
                .startSpan();
        SpanFinisher finisher = new SpanFinisher(span);
        ServerCall<ReqT, RespT> traceCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
                responseHeaders.put(TRACE_ID_METADATA_KEY, span.getSpanContext().getTraceId());
                super.sendHeaders(responseHeaders);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                trailers.put(TRACE_ID_METADATA_KEY, span.getSpanContext().getTraceId());
                if (!status.isOk()) {
                    markStatusError(span, status);
                }
                super.close(status, trailers);
                finisher.end();
            }
        };
        try {
            ServerCall.Listener<ReqT> listener;
            try (Scope ignored = span.makeCurrent()) {
                listener = next.startCall(traceCall, headers);
            }
            return new TraceContextServerCallListener<>(listener, span, finisher);
        } catch (RuntimeException error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
            finisher.end();
            throw error;
        }
    }

    private static void markStatusError(Span span, Status status) {
        span.setStatus(StatusCode.ERROR, status.getCode().name());
        span.setAttribute("rpc.grpc.status_code", status.getCode().name());
        stableErrorCode(status.getDescription()).ifPresent(errorCode -> span.setAttribute("error_code", errorCode));
        if (status.getCode() == Status.Code.UNKNOWN || status.getCode() == Status.Code.INTERNAL) {
            span.recordException(status.asRuntimeException());
        }
    }

    private static java.util.Optional<String> stableErrorCode(String description) {
        if (description == null || !STABLE_ERROR_CODE.matcher(description).matches()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(description);
    }

    private enum MetadataGetter implements TextMapGetter<Metadata> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            try {
                return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }

    private static final class TraceContextServerCallListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
        private final Span span;
        private final SpanFinisher finisher;

        private TraceContextServerCallListener(
                ServerCall.Listener<ReqT> delegate, Span span, SpanFinisher finisher) {
            super(delegate);
            this.span = span;
            this.finisher = finisher;
        }

        @Override
        public void onMessage(ReqT message) {
            try (Scope ignored = span.makeCurrent()) {
                super.onMessage(message);
            } catch (RuntimeException error) {
                finishWithError(error);
                throw error;
            }
        }

        @Override
        public void onHalfClose() {
            try (Scope ignored = span.makeCurrent()) {
                super.onHalfClose();
            } catch (RuntimeException error) {
                finishWithError(error);
                throw error;
            }
        }

        @Override
        public void onCancel() {
            try (Scope ignored = span.makeCurrent()) {
                super.onCancel();
            } catch (RuntimeException error) {
                finishWithError(error);
                throw error;
            } finally {
                finisher.end();
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = span.makeCurrent()) {
                super.onComplete();
            } catch (RuntimeException error) {
                finishWithError(error);
                throw error;
            }
        }

        @Override
        public void onReady() {
            try (Scope ignored = span.makeCurrent()) {
                super.onReady();
            } catch (RuntimeException error) {
                finishWithError(error);
                throw error;
            }
        }

        private void finishWithError(RuntimeException error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
            finisher.end();
        }
    }

    private static final class SpanFinisher {
        private final Span span;
        private final AtomicBoolean ended = new AtomicBoolean();

        private SpanFinisher(Span span) {
            this.span = span;
        }

        private void end() {
            if (ended.compareAndSet(false, true)) {
                span.end();
            }
        }
    }
}
