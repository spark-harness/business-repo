package com.spark.applicant.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.common.spring.cleanarchitecture.grpc.OpenTelemetryGrpcServerInterceptor;
import com.spark.applicant.application.auth.AuthPolicy;
import com.spark.applicant.application.auth.RefreshTokenUseCase;
import com.spark.applicant.application.auth.SendOtpUseCase;
import com.spark.applicant.application.auth.VerifyOtpUseCase;
import com.spark.applicant.infrastructure.auth.InMemoryApplicantRepository;
import com.spark.applicant.infrastructure.auth.InMemoryIdempotencyRepository;
import com.spark.applicant.infrastructure.auth.InMemoryOtpChallengeRepository;
import com.spark.applicant.infrastructure.auth.InMemorySessionTokenStore;
import com.spark.applicant.infrastructure.auth.SimpleTokenService;
import com.spark.applicant.infrastructure.auth.TestSmsCodeSender;
import com.vesta.lendora.applicant.v1.ApplicantAuthServiceGrpc;
import com.vesta.lendora.applicant.v1.RefreshTokenRequest;
import com.vesta.lendora.applicant.v1.SendOtpRequest;
import com.vesta.lendora.applicant.v1.SendOtpResponse;
import com.vesta.lendora.applicant.v1.VerifyOtpRequest;
import com.vesta.lendora.applicant.v1.VerifyOtpResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

class ApplicantAuthGrpcAdapterTest {
    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACEPARENT_METADATA_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    @RegisterExtension
    static final OpenTelemetryExtension OPEN_TELEMETRY = OpenTelemetryExtension.create();

    private io.grpc.Server server;
    private ManagedChannel channel;
    private ApplicantAuthServiceGrpc.ApplicantAuthServiceBlockingStub stub;
    private TraceMetadataCapture traceMetadataCapture;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "applicant-auth-test-" + UUID.randomUUID();
        Clock clock = Clock.systemUTC();
        AuthPolicy policy = AuthPolicy.defaults();
        InMemoryOtpChallengeRepository otpRepository = new InMemoryOtpChallengeRepository(clock);
        InMemoryIdempotencyRepository idempotencyRepository = new InMemoryIdempotencyRepository(clock);
        InMemorySessionTokenStore tokenStore = new InMemorySessionTokenStore(clock);
        SimpleTokenService tokenService = new SimpleTokenService(clock);
        SendOtpUseCase sendOtpUseCase = new SendOtpUseCase(
                otpRepository, idempotencyRepository, new TestSmsCodeSender("123456"), policy, clock);
        VerifyOtpUseCase verifyOtpUseCase = new VerifyOtpUseCase(
                otpRepository,
                new InMemoryApplicantRepository(clock),
                tokenStore,
                idempotencyRepository,
                tokenService,
                policy,
                clock);
        RefreshTokenUseCase refreshTokenUseCase =
                new RefreshTokenUseCase(tokenStore, idempotencyRepository, tokenService, policy, clock);

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new ApplicantAuthGrpcAdapter(
                                sendOtpUseCase,
                                verifyOtpUseCase,
                                refreshTokenUseCase,
                                new SimpleMeterRegistry()),
                        new OpenTelemetryGrpcServerInterceptor(OPEN_TELEMETRY.getOpenTelemetry())))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        traceMetadataCapture = new TraceMetadataCapture();
        stub = ApplicantAuthServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, traceMetadataCapture));
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void sendOtp_whenRequestIsValid_shouldReturnChallenge() {
        SendOtpResponse response = stub.sendOtp(SendOtpRequest.newBuilder()
                .setCountryCode("+852")
                .setPhone("91234567")
                .setIdempotencyKey("idem-send-1")
                .build());

        assertThat(response.getChallengeId()).isNotBlank();
        assertThat(response.getExpiresInSec()).isEqualTo(300);
        assertThat(response.getResendAfterSec()).isEqualTo(60);
        assertThat(traceMetadataCapture.traceId()).matches("[0-9a-f]{32}");
        assertThat(traceMetadataCapture.traceId()).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void sendOtp_whenCountryIsUnsupported_shouldReturnInvalidArgument() {
        SendOtpRequest request = SendOtpRequest.newBuilder()
                .setCountryCode("+86")
                .setPhone("13800138000")
                .setIdempotencyKey("idem-send-1")
                .build();

        assertThatThrownBy(() -> stub.sendOtp(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(traceMetadataCapture.traceId()).matches("[0-9a-f]{32}");
        assertThat(traceMetadataCapture.traceId()).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void sendOtp_whenTraceparentMetadataExists_shouldContinueIncomingTrace() {
        String incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        Metadata metadata = new Metadata();
        metadata.put(TRACEPARENT_METADATA_KEY, "00-" + incomingTraceId + "-00f067aa0ba902b7-01");
        ApplicantAuthServiceGrpc.ApplicantAuthServiceBlockingStub tracedStub =
                stub.withInterceptors(new RequestMetadataInjector(metadata));

        SendOtpResponse response = tracedStub.sendOtp(SendOtpRequest.newBuilder()
                .setCountryCode("+852")
                .setPhone("92334567")
                .setIdempotencyKey("idem-send-traced")
                .build());

        assertThat(response.getChallengeId()).isNotBlank();
        assertThat(traceMetadataCapture.traceId()).isEqualTo(incomingTraceId);
    }

    @Test
    void verifyOtp_whenCodeIsCorrect_shouldReturnTokens() {
        SendOtpResponse challenge = stub.sendOtp(SendOtpRequest.newBuilder()
                .setCountryCode("+852")
                .setPhone("91234567")
                .setIdempotencyKey("idem-send-1")
                .build());

        VerifyOtpResponse response = stub.verifyOtp(VerifyOtpRequest.newBuilder()
                .setChallengeId(challenge.getChallengeId())
                .setCode("123456")
                .setIdempotencyKey("idem-verify-1")
                .build());

        assertThat(response.getApplicantId()).startsWith("applicant_");
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getExpiresInSec()).isEqualTo(3600);
        assertThat(response.getRefreshExpiresInSec()).isEqualTo(3600);
    }

    @Test
    void refreshToken_whenRefreshTokenIsValid_shouldReturnAccessToken() {
        SendOtpResponse challenge = stub.sendOtp(SendOtpRequest.newBuilder()
                .setCountryCode("+852")
                .setPhone("91234567")
                .setIdempotencyKey("idem-send-1")
                .build());
        VerifyOtpResponse login = stub.verifyOtp(VerifyOtpRequest.newBuilder()
                .setChallengeId(challenge.getChallengeId())
                .setCode("123456")
                .setIdempotencyKey("idem-verify-1")
                .build());

        String accessToken = stub.refreshToken(RefreshTokenRequest.newBuilder()
                        .setRefreshToken(login.getRefreshToken())
                        .setIdempotencyKey("idem-refresh-1")
                        .build())
                .getAccessToken();

        assertThat(accessToken).isNotBlank();
        assertThat(accessToken).isNotEqualTo(login.getAccessToken());
    }

    private static final class TraceMetadataCapture implements ClientInterceptor {
        private String traceId;

        String traceId() {
            return traceId;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            capture(headers);
                            super.onHeaders(headers);
                        }

                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            capture(trailers);
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }

        private void capture(Metadata metadata) {
            String value = metadata.get(TRACE_ID_METADATA_KEY);
            if (value != null) {
                traceId = value;
            }
        }
    }

    private static final class RequestMetadataInjector implements ClientInterceptor {
        private final Metadata metadata;

        private RequestMetadataInjector(Metadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.merge(metadata);
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
