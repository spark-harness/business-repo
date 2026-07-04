package com.spark.quote.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import com.spark.quote.application.CreateQuoteUseCase;
import com.spark.quote.application.GetQuoteUseCase;
import com.spark.quote.domain.Quote;
import com.spark.quote.infrastructure.InMemoryQuoteRepository;
import com.vesta.lendora.quote.v1.CreateQuoteRequest;
import com.vesta.lendora.quote.v1.CreateQuoteResponse;
import com.vesta.lendora.quote.v1.GetQuoteRequest;
import com.vesta.lendora.quote.v1.GetQuoteResponse;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
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
import java.math.BigDecimal;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteGrpcAdapterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryQuoteRepository repository = new InMemoryQuoteRepository();
    private io.grpc.Server server;
    private ManagedChannel channel;
    private QuoteServiceGrpc.QuoteServiceBlockingStub stub;
    private QuoteServiceGrpc.QuoteServiceBlockingStub unauthenticatedStub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "quote-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new QuoteGrpcAdapter(new CreateQuoteUseCase(repository, clock), new GetQuoteUseCase(repository, clock)),
                        new RequestPrincipalGrpcServerInterceptor("QUOTE-AUTH-0001")))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = QuoteServiceGrpc.newBlockingStub(ClientInterceptors.intercept(
                channel, new ApplicantMetadataInjector("applicant_001")));
        unauthenticatedStub = QuoteServiceGrpc.newBlockingStub(channel);
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
    void createQuote_whenRequestIsValid_shouldReturnPricing() {
        CreateQuoteResponse response = stub.createQuote(CreateQuoteRequest.newBuilder()
                .setProductCode("PIL")
                .setAmount("100000.00")
                .setTerm(12)
                .setPurpose("debt_consolidation")
                .setTraceId("trace-1")
                .build());

        assertThat(response.getQuote().getQuoteId()).startsWith("quote_");
        assertThat(response.getQuote().getMonthly()).isEqualTo("8560.75");
        assertThat(response.getQuote().getApr()).isEqualTo("0.0520");
        assertThat(response.getQuote().getTotalInterest()).isEqualTo("2729.00");
        assertThat(response.getQuote().getTotalPayable()).isEqualTo("102729.00");
        assertThat(response.getQuote().getValidUntil()).isEqualTo("2026-06-28T00:30:00Z");
    }

    @Test
    void getQuote_whenApplicantOwnsQuote_shouldReturnPersistedQuote() {
        CreateQuoteResponse created = stub.createQuote(CreateQuoteRequest.newBuilder()
                .setProductCode("PIL")
                .setAmount("100000.00")
                .setTerm(12)
                .setPurpose("debt_consolidation")
                .build());

        GetQuoteResponse response = stub.getQuote(GetQuoteRequest.newBuilder()
                .setQuoteId(created.getQuote().getQuoteId())
                .build());

        assertThat(response.getQuote().getQuoteId()).isEqualTo(created.getQuote().getQuoteId());
        assertThat(response.getQuote().getProductCode()).isEqualTo("PIL");
        assertThat(response.getQuote().getAmount()).isEqualTo("100000.00");
        assertThat(response.getQuote().getPurpose()).isEqualTo("debt_consolidation");
    }

    @Test
    void createQuote_whenAmountIsOutOfRange_shouldReturnInvalidArgument() {
        CreateQuoteRequest request = CreateQuoteRequest.newBuilder()
                .setProductCode("PIL")
                .setAmount("4999.99")
                .setTerm(12)
                .setPurpose("debt_consolidation")
                .build();

        assertThatThrownBy(() -> stub.createQuote(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(status.getDescription()).isEqualTo("QUOTE-PARAM-0002");
                });
    }

    @Test
    void createQuote_withoutApplicantMetadata_shouldReturnQuoteAuthCode() {
        CreateQuoteRequest request = CreateQuoteRequest.newBuilder()
                .setProductCode("PIL")
                .setAmount("100000.00")
                .setTerm(12)
                .setPurpose("debt_consolidation")
                .build();

        assertThatThrownBy(() -> unauthenticatedStub.createQuote(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(status.getDescription()).isEqualTo("QUOTE-AUTH-0001");
                });
    }

    @Test
    void getQuote_whenQuoteDoesNotExist_shouldReturnNotFound() {
        GetQuoteRequest request = GetQuoteRequest.newBuilder().setQuoteId("quote_missing").build();

        assertThatThrownBy(() -> stub.getQuote(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(status.getDescription()).isEqualTo("QUOTE-STATE-0001");
                });
    }

    @Test
    void getQuote_whenApplicantDoesNotOwnQuote_shouldReturnPermissionDenied() {
        repository.save(quote("quote_001", "applicant_002", Instant.parse("2026-06-28T00:30:00Z")));

        GetQuoteRequest request = GetQuoteRequest.newBuilder().setQuoteId("quote_001").build();

        assertThatThrownBy(() -> stub.getQuote(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(status.getDescription()).isEqualTo("QUOTE-PERMISSION-0001");
                });
    }

    @Test
    void getQuote_whenQuoteExpired_shouldReturnFailedPrecondition() {
        repository.save(quote("quote_001", "applicant_001", Instant.parse("2026-06-27T23:59:00Z")));

        GetQuoteRequest request = GetQuoteRequest.newBuilder().setQuoteId("quote_001").build();

        assertThatThrownBy(() -> stub.getQuote(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                    assertThat(status.getDescription()).isEqualTo("QUOTE-STATE-0002");
                });
    }

    private static final class ApplicantMetadataInjector implements ClientInterceptor {
        private final String applicantId;

        private ApplicantMetadataInjector(String applicantId) {
            this.applicantId = applicantId;
        }

        @Override
        public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions callOptions, io.grpc.Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY, applicantId);
                    super.start(
                            new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {},
                            headers);
                }
            };
        }
    }

    private Quote quote(String quoteId, String applicantId, Instant validUntil) {
        return new Quote(
                quoteId,
                applicantId,
                "PIL",
                new BigDecimal("100000.00"),
                12,
                "debt_consolidation",
                new BigDecimal("8560.75"),
                new BigDecimal("0.0520"),
                new BigDecimal("2729.00"),
                new BigDecimal("102729.00"),
                validUntil,
                "trace-1",
                clock.instant());
    }
}
