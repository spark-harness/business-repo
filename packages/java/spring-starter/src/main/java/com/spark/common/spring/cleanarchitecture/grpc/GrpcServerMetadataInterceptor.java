package com.spark.common.spring.cleanarchitecture.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;

public class GrpcServerMetadataInterceptor implements ServerInterceptor, Ordered {
    public static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Pattern STABLE_ERROR_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> metadataCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
                putTraceId(responseHeaders);
                super.sendHeaders(responseHeaders);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                putTraceId(trailers);
                if (!status.isOk()) {
                    Span span = Span.current();
                    span.setStatus(StatusCode.ERROR, status.getCode().name());
                    stableErrorCode(status.getDescription())
                            .ifPresent(errorCode -> span.setAttribute("error_code", errorCode));
                }
                super.close(status, trailers);
            }

            private void putTraceId(Metadata metadata) {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    metadata.put(TRACE_ID_METADATA_KEY, span.getSpanContext().getTraceId());
                }
            }
        };
        return next.startCall(metadataCall, headers);
    }

    private static Optional<String> stableErrorCode(String description) {
        if (description == null || !STABLE_ERROR_CODE.matcher(description).matches()) {
            return Optional.empty();
        }
        return Optional.of(description);
    }
}
