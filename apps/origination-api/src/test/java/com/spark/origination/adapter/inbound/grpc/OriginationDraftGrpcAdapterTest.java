package com.spark.origination.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.origination.application.AdvanceApplicationStepUseCase;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.ApplicationStatus;
import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import com.spark.origination.infrastructure.InMemoryLoanApplicationRepository;
import com.vesta.lendora.origination.v1.AdvanceApplicationStepRequest;
import com.vesta.lendora.origination.v1.AdvanceApplicationStepResponse;
import com.vesta.lendora.origination.v1.OriginationDraftServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OriginationDraftGrpcAdapterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryLoanApplicationRepository repository = new InMemoryLoanApplicationRepository();
    private io.grpc.Server server;
    private ManagedChannel channel;
    private OriginationDraftServiceGrpc.OriginationDraftServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "origination-draft-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new OriginationDraftGrpcAdapter(new AdvanceApplicationStepUseCase(repository, clock)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = OriginationDraftServiceGrpc.newBlockingStub(channel);
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
    void advanceApplicationStep_whenOwnerAndTargetAreValid_shouldUpdateCurrentStep() {
        repository.save(application("app_001", "applicant_001"));

        AdvanceApplicationStepResponse response = stub.advanceApplicationStep(AdvanceApplicationStepRequest.newBuilder()
                .setApplicantId("applicant_001")
                .setApplicationId("app_001")
                .setTargetStep(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION)
                .build());

        assertThat(response.getApplicationId()).isEqualTo("app_001");
        assertThat(response.getCurrentStep())
                .isEqualTo(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION);
        assertThat(repository.findById("app_001").orElseThrow().currentStep())
                .isEqualTo(ApplicationStep.IDENTITY_INFORMATION);
    }

    @Test
    void advanceApplicationStep_whenApplicationIdIsMissing_shouldReturnInvalidArgument() {
        AdvanceApplicationStepRequest request = AdvanceApplicationStepRequest.newBuilder()
                .setApplicantId("applicant_001")
                .setTargetStep(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION)
                .build();

        assertThatThrownBy(() -> stub.advanceApplicationStep(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void advanceApplicationStep_whenApplicantDoesNotOwnApplication_shouldReturnPermissionDenied() {
        repository.save(application("app_001", "applicant_001"));

        AdvanceApplicationStepRequest request = AdvanceApplicationStepRequest.newBuilder()
                .setApplicantId("applicant_002")
                .setApplicationId("app_001")
                .setTargetStep(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION)
                .build();

        assertThatThrownBy(() -> stub.advanceApplicationStep(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void advanceApplicationStep_whenTargetIsLoanRequest_shouldReturnInvalidArgument() {
        repository.save(application("app_001", "applicant_001"));

        AdvanceApplicationStepRequest request = AdvanceApplicationStepRequest.newBuilder()
                .setApplicantId("applicant_001")
                .setApplicationId("app_001")
                .setTargetStep(com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_LOAN_REQUEST)
                .build();

        assertThatThrownBy(() -> stub.advanceApplicationStep(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private LoanApplication application(String applicationId, String applicantId) {
        return new LoanApplication(
                applicationId,
                applicantId,
                "PIL",
                new LoanTerms(new BigDecimal("100000.00"), 12, "debt_consolidation"),
                new AcceptedQuote(
                        "quote_1",
                        applicantId,
                        new BigDecimal("100000.00"),
                        12,
                        "debt_consolidation",
                        new BigDecimal("8560.75"),
                        new BigDecimal("0.0520"),
                        new BigDecimal("2729.00"),
                        new BigDecimal("102729.00"),
                        Instant.parse("2026-06-28T00:30:00Z")),
                ApplicationStatus.DRAFT,
                ApplicationStep.LOAN_REQUEST,
                clock.instant(),
                clock.instant());
    }
}
