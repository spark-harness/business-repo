package com.spark.origination.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.support.TestPrincipal;
import com.vesta.lendora.quote.v1.GetQuoteRequest;
import com.vesta.lendora.quote.v1.GetQuoteResponse;
import com.vesta.lendora.quote.v1.Quote;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

class GrpcQuoteGatewayTest {
    private FakeQuoteService quoteService;
    private Server server;
    private ManagedChannel channel;
    private GrpcQuoteGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "quote-gateway-test-" + UUID.randomUUID();
        quoteService = new FakeQuoteService();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(quoteService, new ApplicantMetadataCapture(quoteService)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        gateway = new GrpcQuoteGateway(QuoteServiceGrpc.newBlockingStub(channel), Duration.ofSeconds(1));
        TestPrincipal.set("applicant_001");
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void get_whenQuoteExists_returnsAcceptedQuoteAndForwardsApplicantMetadata() {
        quoteService.quote = quote("quote_1");

        AcceptedQuote quote = gateway.get("quote_1");

        assertThat(quoteService.lastRequest.getQuoteId()).isEqualTo("quote_1");
        assertThat(quoteService.lastApplicantId).isEqualTo("applicant_001");
        assertThat(quote.quoteId()).isEqualTo("quote_1");
        assertThat(quote.applicantId()).isEqualTo("applicant_001");
        assertThat(quote.amount()).isEqualByComparingTo("100000.00");
        assertThat(quote.term()).isEqualTo(12);
        assertThat(quote.purpose()).isEqualTo("debt_consolidation");
    }

    @Test
    void get_whenQuoteApiReturnsStableStatuses_mapsApplicationExceptions() {
        assertMaps(Status.NOT_FOUND.withDescription("QUOTE-STATE-0001"), QuoteNotFoundException.class);
        assertMaps(Status.FAILED_PRECONDITION.withDescription("QUOTE-STATE-0002"), QuoteExpiredException.class);
        assertMaps(Status.PERMISSION_DENIED.withDescription("QUOTE-PERMISSION-0001"), ForbiddenException.class);
        assertMaps(Status.UNAVAILABLE.withDescription("QUOTE-SYSTEM-0001"), QuoteUnavailableException.class);
        assertMaps(Status.UNKNOWN.withDescription("QUOTE-SYSTEM-0001"), QuoteUnavailableException.class);
    }

    @Test
    void get_whenQuoteApiReturnsMalformedQuote_preservesCauseForDiagnostics() {
        quoteService.quote = quote("quote_1").toBuilder().setAmount("not-a-decimal").build();

        assertThatThrownBy(() -> gateway.get("quote_1"))
                .isInstanceOf(QuoteUnavailableException.class)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void get_withoutPrincipal_mapsUnavailable() {
        TestPrincipal.clear();

        assertThatThrownBy(() -> gateway.get("quote_1")).isInstanceOf(QuoteUnavailableException.class);
    }

    private void assertMaps(Status status, Class<? extends RuntimeException> exceptionType) {
        quoteService.error = status.asRuntimeException();

        assertThatThrownBy(() -> gateway.get("quote_1"))
                .isInstanceOf(exceptionType)
                .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class);
    }

    private static Quote quote(String quoteId) {
        return Quote.newBuilder()
                .setQuoteId(quoteId)
                .setProductCode("PIL")
                .setAmount("100000.00")
                .setTerm(12)
                .setPurpose("debt_consolidation")
                .setMonthly("8560.75")
                .setApr("0.0520")
                .setTotalInterest("2729.00")
                .setTotalPayable("102729.00")
                .setValidUntil("2026-06-28T00:30:00Z")
                .build();
    }

    private static final class FakeQuoteService extends QuoteServiceGrpc.QuoteServiceImplBase {
        private Quote quote;
        private RuntimeException error;
        private GetQuoteRequest lastRequest;
        private String lastApplicantId;

        @Override
        public void getQuote(GetQuoteRequest request, StreamObserver<GetQuoteResponse> responseObserver) {
            lastRequest = request;
            if (error != null) {
                responseObserver.onError(error);
                return;
            }
            responseObserver.onNext(GetQuoteResponse.newBuilder().setQuote(quote).build());
            responseObserver.onCompleted();
        }
    }

    private static final class ApplicantMetadataCapture implements ServerInterceptor {
        private final FakeQuoteService quoteService;

        private ApplicantMetadataCapture(FakeQuoteService quoteService) {
            this.quoteService = quoteService;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            quoteService.lastApplicantId = headers.get(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY);
            return next.startCall(call, headers);
        }
    }
}
