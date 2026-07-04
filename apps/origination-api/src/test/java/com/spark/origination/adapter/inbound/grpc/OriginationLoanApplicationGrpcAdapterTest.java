package com.spark.origination.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import com.spark.origination.application.AdvanceApplicationStepUseCase;
import com.spark.origination.application.CreateLoanApplicationUseCase;
import com.spark.origination.application.GetLoanApplicationUseCase;
import com.spark.origination.application.PatchLoanApplicationUseCase;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.infrastructure.InMemoryIdempotencyRepository;
import com.spark.origination.infrastructure.InMemoryLoanApplicationRepository;
import com.vesta.lendora.origination.v1.CreateLoanApplicationRequest;
import com.vesta.lendora.origination.v1.CreateLoanApplicationResponse;
import com.vesta.lendora.origination.v1.GetLoanApplicationRequest;
import com.vesta.lendora.origination.v1.GetLoanApplicationResponse;
import com.vesta.lendora.origination.v1.LoanTerms;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceGrpc;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceAdvanceApplicationStepRequest;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceAdvanceApplicationStepResponse;
import com.vesta.lendora.origination.v1.UpdateLoanApplicationRequest;
import com.vesta.lendora.origination.v1.UpdateLoanApplicationResponse;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OriginationLoanApplicationGrpcAdapterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryLoanApplicationRepository applications = new InMemoryLoanApplicationRepository();
    private final InMemoryIdempotencyRepository idempotency = new InMemoryIdempotencyRepository();
    private final FakeQuoteGateway quoteGateway = new FakeQuoteGateway();
    private io.grpc.Server server;
    private ManagedChannel channel;
    private OriginationLoanApplicationServiceGrpc.OriginationLoanApplicationServiceBlockingStub stub;
    private OriginationLoanApplicationServiceGrpc.OriginationLoanApplicationServiceBlockingStub unauthenticatedStub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "origination-loan-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new OriginationLoanApplicationGrpcAdapter(
                                new CreateLoanApplicationUseCase(applications, idempotency, quoteGateway, clock),
                                new GetLoanApplicationUseCase(applications),
                                new PatchLoanApplicationUseCase(applications, idempotency, quoteGateway, clock),
                                new AdvanceApplicationStepUseCase(applications, clock)),
                        new RequestPrincipalGrpcServerInterceptor("ORIGINATION-AUTH-0001")))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = OriginationLoanApplicationServiceGrpc.newBlockingStub(ClientInterceptors.intercept(
                channel, new ApplicantMetadataInjector("applicant_001")));
        unauthenticatedStub = OriginationLoanApplicationServiceGrpc.newBlockingStub(channel);
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
    void createGetAndUpdateLoanApplication_whenQuoteIsValid_shouldPersistDraft() {
        quoteGateway.add(quote("quote_1", "applicant_001", "100000.00", 12, "2026-06-28T00:30:00Z"));
        quoteGateway.add(quote("quote_2", "applicant_001", "120000.00", 24, "2026-06-28T00:30:00Z"));

        CreateLoanApplicationResponse created = stub.createLoanApplication(CreateLoanApplicationRequest.newBuilder()
                .setProductCode("PIL")
                .setQuoteId("quote_1")
                .setLoan(loan("100000.00", 12))
                .setIdempotencyKey("idem-create")
                .build());

        assertThat(created.getApplicationId()).startsWith("app_");
        assertThat(created.getStatus()).isEqualTo("draft");
        assertThat(created.getCurrentStep()).isEqualTo("loan_request");

        UpdateLoanApplicationResponse updated = stub.updateLoanApplication(UpdateLoanApplicationRequest.newBuilder()
                .setApplicationId(created.getApplicationId())
                .setQuoteId("quote_2")
                .setLoan(loan("120000.00", 24))
                .setIdempotencyKey("idem-update")
                .build());

        assertThat(updated.getApplicationId()).isEqualTo(created.getApplicationId());
        assertThat(updated.getStatus()).isEqualTo("draft");
        assertThat(updated.getCurrentStep()).isEqualTo("loan_request");

        GetLoanApplicationResponse loaded = stub.getLoanApplication(GetLoanApplicationRequest.newBuilder()
                .setApplicationId(created.getApplicationId())
                .build());

        assertThat(loaded.getApplicationId()).isEqualTo(created.getApplicationId());
        assertThat(loaded.getLoan().getAmount()).isEqualTo("120000.00");
        assertThat(loaded.getLoan().getTerm()).isEqualTo(24);
        assertThat(loaded.getAcceptedQuote().getQuoteId()).isEqualTo("quote_2");
    }

    @Test
    void advanceApplicationStep_whenApplicationExists_shouldMoveDraftStep() {
        quoteGateway.add(quote("quote_1", "applicant_001", "100000.00", 12, "2026-06-28T00:30:00Z"));
        CreateLoanApplicationResponse created = stub.createLoanApplication(CreateLoanApplicationRequest.newBuilder()
                .setProductCode("PIL")
                .setQuoteId("quote_1")
                .setLoan(loan("100000.00", 12))
                .setIdempotencyKey("idem-create")
                .build());

        OriginationLoanApplicationServiceAdvanceApplicationStepResponse advanced = stub.advanceApplicationStep(
                OriginationLoanApplicationServiceAdvanceApplicationStepRequest.newBuilder()
                        .setApplicationId(created.getApplicationId())
                        .setTargetStep(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION)
                        .build());

        assertThat(advanced.getApplicationId()).isEqualTo(created.getApplicationId());
        assertThat(advanced.getCurrentStep())
                .isEqualTo(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION);
    }

    @Test
    void createLoanApplication_withoutIdempotencyKey_shouldReturnInvalidArgument() {
        quoteGateway.add(quote("quote_1", "applicant_001", "100000.00", 12, "2026-06-28T00:30:00Z"));

        CreateLoanApplicationRequest request = CreateLoanApplicationRequest.newBuilder()
                .setProductCode("PIL")
                .setQuoteId("quote_1")
                .setLoan(loan("100000.00", 12))
                .build();

        assertThatThrownBy(() -> stub.createLoanApplication(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(status.getDescription()).isEqualTo("ORIGINATION-PARAM-0001");
                });
    }

    @Test
    void createLoanApplication_withoutApplicantMetadata_shouldReturnUnauthenticated() {
        CreateLoanApplicationRequest request = CreateLoanApplicationRequest.newBuilder()
                .setProductCode("PIL")
                .setQuoteId("quote_1")
                .setLoan(loan("100000.00", 12))
                .setIdempotencyKey("idem-create")
                .build();

        assertThatThrownBy(() -> unauthenticatedStub.createLoanApplication(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(status.getDescription()).isEqualTo("ORIGINATION-AUTH-0001");
                });
    }

    @Test
    void getLoanApplication_whenApplicationDoesNotExist_shouldReturnNotFound() {
        GetLoanApplicationRequest request = GetLoanApplicationRequest.newBuilder()
                .setApplicationId("app_missing")
                .build();

        assertThatThrownBy(() -> stub.getLoanApplication(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(status.getDescription()).isEqualTo("ORIGINATION-STATE-0001");
                });
    }

    @Test
    void createLoanApplication_whenQuoteExpired_shouldReturnFailedPrecondition() {
        quoteGateway.add(quote("quote_expired", "applicant_001", "100000.00", 12, "2026-06-27T23:59:00Z"));

        CreateLoanApplicationRequest request = CreateLoanApplicationRequest.newBuilder()
                .setProductCode("PIL")
                .setQuoteId("quote_expired")
                .setLoan(loan("100000.00", 12))
                .setIdempotencyKey("idem-expired")
                .build();

        assertThatThrownBy(() -> stub.createLoanApplication(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> {
                    Status status = ((StatusRuntimeException) error).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                    assertThat(status.getDescription()).isEqualTo("ORIGINATION-QUOTE-0002");
                });
    }

    private static LoanTerms loan(String amount, int term) {
        return LoanTerms.newBuilder()
                .setAmount(amount)
                .setTerm(term)
                .setPurpose("debt_consolidation")
                .build();
    }

    private AcceptedQuote quote(String quoteId, String applicantId, String amount, int term, String validUntil) {
        return new AcceptedQuote(
                quoteId,
                applicantId,
                new BigDecimal(amount),
                term,
                "debt_consolidation",
                new BigDecimal("8560.75"),
                new BigDecimal("0.0520"),
                new BigDecimal("2729.00"),
                new BigDecimal("102729.00"),
                Instant.parse(validUntil));
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

    private static final class FakeQuoteGateway implements QuoteGateway {
        private final Map<String, AcceptedQuote> quotes = new LinkedHashMap<>();

        @Override
        public AcceptedQuote get(String quoteId) {
            AcceptedQuote quote = quotes.get(quoteId);
            if (quote == null) {
                throw new QuoteNotFoundException();
            }
            return quote;
        }

        private void add(AcceptedQuote quote) {
            quotes.put(quote.quoteId(), quote);
        }
    }
}
