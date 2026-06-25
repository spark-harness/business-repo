package com.spark.applicant.adapter.inbound.grpc;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.RefreshTokenCommand;
import com.spark.applicant.application.auth.RefreshTokenResult;
import com.spark.applicant.application.auth.RefreshTokenUseCase;
import com.spark.applicant.application.auth.SendOtpCommand;
import com.spark.applicant.application.auth.SendOtpResult;
import com.spark.applicant.application.auth.SendOtpUseCase;
import com.spark.applicant.application.auth.VerifyOtpCommand;
import com.spark.applicant.application.auth.VerifyOtpResult;
import com.spark.applicant.application.auth.VerifyOtpUseCase;
import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.vesta.lendora.applicant.v1.ApplicantAuthServiceGrpc;
import com.vesta.lendora.applicant.v1.RefreshTokenRequest;
import com.vesta.lendora.applicant.v1.RefreshTokenResponse;
import com.vesta.lendora.applicant.v1.SendOtpRequest;
import com.vesta.lendora.applicant.v1.SendOtpResponse;
import com.vesta.lendora.applicant.v1.VerifyOtpRequest;
import com.vesta.lendora.applicant.v1.VerifyOtpResponse;
import io.grpc.BindableService;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

@InboundAdapter
public class ApplicantAuthGrpcAdapter implements BindableService {
    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    private final SendOtpUseCase sendOtpUseCase;
    private final VerifyOtpUseCase verifyOtpUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final ApplicantAuthTelemetry telemetry;
    private final Tracer tracer;
    private final ApplicantAuthServiceGrpc.ApplicantAuthServiceImplBase delegate = new GrpcDelegate();

    @Autowired
    public ApplicantAuthGrpcAdapter(
            SendOtpUseCase sendOtpUseCase,
            VerifyOtpUseCase verifyOtpUseCase,
            RefreshTokenUseCase refreshTokenUseCase,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {
        this.sendOtpUseCase = sendOtpUseCase;
        this.verifyOtpUseCase = verifyOtpUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.telemetry = new ApplicantAuthTelemetry(meterRegistry);
        this.tracer = openTelemetry.getTracer("applicant-api-grpc");
    }

    ApplicantAuthGrpcAdapter(
            SendOtpUseCase sendOtpUseCase,
            VerifyOtpUseCase verifyOtpUseCase,
            RefreshTokenUseCase refreshTokenUseCase,
            MeterRegistry meterRegistry) {
        this(sendOtpUseCase, verifyOtpUseCase, refreshTokenUseCase, meterRegistry, OpenTelemetry.noop());
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerInterceptors.intercept(delegate.bindService(), new TraceMetadataServerInterceptor(tracer));
    }

    private void sendOtp(SendOtpRequest request, StreamObserver<SendOtpResponse> responseObserver) {
        try {
            SendOtpResult result = telemetry.record("send_otp", () -> sendOtpUseCase.sendOtp(
                    new SendOtpCommand(request.getCountryCode(), request.getPhone(), request.getIdempotencyKey())));
            responseObserver.onNext(SendOtpResponse.newBuilder()
                    .setChallengeId(result.challengeId())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .setResendAfterSec(Math.toIntExact(result.resendAfter().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private void verifyOtp(VerifyOtpRequest request, StreamObserver<VerifyOtpResponse> responseObserver) {
        try {
            VerifyOtpResult result = telemetry.record("verify_otp", () -> verifyOtpUseCase.verifyOtp(
                    new VerifyOtpCommand(request.getChallengeId(), request.getCode(), request.getIdempotencyKey())));
            responseObserver.onNext(VerifyOtpResponse.newBuilder()
                    .setAccessToken(result.accessToken())
                    .setRefreshToken(result.refreshToken())
                    .setApplicantId(result.applicantId())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .setRefreshExpiresInSec(Math.toIntExact(result.refreshExpiresIn().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private void refreshToken(RefreshTokenRequest request, StreamObserver<RefreshTokenResponse> responseObserver) {
        try {
            RefreshTokenResult result = telemetry.record("refresh_token", () -> refreshTokenUseCase.refreshToken(
                    new RefreshTokenCommand(request.getRefreshToken(), request.getIdempotencyKey())));
            responseObserver.onNext(RefreshTokenResponse.newBuilder()
                    .setAccessToken(result.accessToken())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private Status toStatus(ApplicantAuthException error) {
        return switch (error.getMessage()) {
            case "unsupported_country", "idempotency_key_required", "otp_code_invalid",
                    "idempotency_key_conflict" -> Status.INVALID_ARGUMENT;
            case "otp_cooldown_active", "otp_code_expired" -> Status.FAILED_PRECONDITION;
            case "otp_provider_disabled" -> Status.UNAVAILABLE;
            case "otp_too_many_attempts" -> Status.RESOURCE_EXHAUSTED;
            case "token_invalid", "token_expired" -> Status.UNAUTHENTICATED;
            default -> Status.UNKNOWN;
        };
    }

    private final class GrpcDelegate extends ApplicantAuthServiceGrpc.ApplicantAuthServiceImplBase {
        @Override
        public void sendOtp(SendOtpRequest request, StreamObserver<SendOtpResponse> responseObserver) {
            ApplicantAuthGrpcAdapter.this.sendOtp(request, responseObserver);
        }

        @Override
        public void verifyOtp(VerifyOtpRequest request, StreamObserver<VerifyOtpResponse> responseObserver) {
            ApplicantAuthGrpcAdapter.this.verifyOtp(request, responseObserver);
        }

        @Override
        public void refreshToken(RefreshTokenRequest request, StreamObserver<RefreshTokenResponse> responseObserver) {
            ApplicantAuthGrpcAdapter.this.refreshToken(request, responseObserver);
        }
    }

    private static final class TraceMetadataServerInterceptor implements ServerInterceptor {
        private final Tracer tracer;

        private TraceMetadataServerInterceptor(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context parentContext = TraceMetadataPropagator.extract(headers);
            Span span = tracer.spanBuilder(call.getMethodDescriptor().getFullMethodName())
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
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
                        span.recordException(status.asRuntimeException());
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                    }
                    super.close(status, trailers);
                    span.end();
                }
            };
            try {
                ServerCall.Listener<ReqT> listener;
                try (Scope ignored = span.makeCurrent()) {
                    listener = next.startCall(traceCall, headers);
                }
                return new TraceContextServerCallListener<>(listener, span);
            } catch (RuntimeException error) {
                span.recordException(error);
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                span.end();
                throw error;
            }
        }
    }

    private static final class TraceMetadataPropagator {
        private static final TextMapPropagator TRACE_CONTEXT_PROPAGATOR = TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());

        private static Context extract(Metadata headers) {
            return TRACE_CONTEXT_PROPAGATOR.extract(Context.current(), headers, MetadataGetter.INSTANCE);
        }
    }

    private enum MetadataGetter implements TextMapGetter<Metadata> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Metadata carrier) {
            Set<String> keys = carrier.keys().stream()
                    .flatMap(key -> Arrays.stream(key.split(",")))
                    .map(String::trim)
                    .collect(Collectors.toSet());
            return keys;
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            Metadata.Key<String> metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            return carrier.get(metadataKey);
        }
    }

    private static final class TraceContextServerCallListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
        private final Span span;

        private TraceContextServerCallListener(ServerCall.Listener<ReqT> delegate, Span span) {
            super(delegate);
            this.span = span;
        }

        @Override
        public void onMessage(ReqT message) {
            try (Scope ignored = span.makeCurrent()) {
                super.onMessage(message);
            }
        }

        @Override
        public void onHalfClose() {
            try (Scope ignored = span.makeCurrent()) {
                super.onHalfClose();
            }
        }

        @Override
        public void onCancel() {
            try (Scope ignored = span.makeCurrent()) {
                super.onCancel();
            } finally {
                span.end();
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = span.makeCurrent()) {
                super.onComplete();
            }
        }

        @Override
        public void onReady() {
            try (Scope ignored = span.makeCurrent()) {
                super.onReady();
            }
        }
    }
}
