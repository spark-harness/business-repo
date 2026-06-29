package com.spark.origination.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.origination.application.AdvanceApplicationStepCommand;
import com.spark.origination.application.AdvanceApplicationStepUseCase;
import com.spark.origination.application.ApplicationNotFoundException;
import com.spark.origination.application.ApplicationRequiredException;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.InvalidStepException;
import com.spark.origination.application.UnauthorizedException;
import com.spark.origination.domain.LoanApplication;
import com.vesta.lendora.origination.v1.AdvanceApplicationStepRequest;
import com.vesta.lendora.origination.v1.AdvanceApplicationStepResponse;
import com.vesta.lendora.origination.v1.OriginationDraftServiceGrpc;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;

@InboundAdapter
public class OriginationDraftGrpcAdapter implements BindableService {
    private final AdvanceApplicationStepUseCase advanceUseCase;
    private final OriginationDraftServiceGrpc.OriginationDraftServiceImplBase delegate = new GrpcDelegate();

    @Autowired
    public OriginationDraftGrpcAdapter(AdvanceApplicationStepUseCase advanceUseCase) {
        this.advanceUseCase = advanceUseCase;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return delegate.bindService();
    }

    private void advanceApplicationStep(
            AdvanceApplicationStepRequest request,
            StreamObserver<AdvanceApplicationStepResponse> responseObserver) {
        try {
            if (request.getApplicantId() == null || request.getApplicantId().isBlank()) {
                throw new UnauthorizedException();
            }
            LoanApplication application = advanceUseCase.advance(new AdvanceApplicationStepCommand(
                    request.getApplicantId(), request.getApplicationId(), toDomainStep(request.getTargetStep())));
            responseObserver.onNext(AdvanceApplicationStepResponse.newBuilder()
                    .setApplicationId(application.applicationId())
                    .setCurrentStep(toProtoStep(application.currentStep()))
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private com.spark.origination.domain.ApplicationStep toDomainStep(
            com.vesta.lendora.origination.v1.ApplicationStep step) {
        return switch (step) {
            case APPLICATION_STEP_IDENTITY_INFORMATION -> com.spark.origination.domain.ApplicationStep.IDENTITY_INFORMATION;
            default -> throw new InvalidStepException();
        };
    }

    private com.vesta.lendora.origination.v1.ApplicationStep toProtoStep(
            com.spark.origination.domain.ApplicationStep step) {
        return switch (step) {
            case IDENTITY_INFORMATION -> com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION;
            case LOAN_REQUEST -> com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_LOAN_REQUEST;
        };
    }

    private Status toStatus(RuntimeException error) {
        if (error instanceof ApplicationRequiredException || error instanceof InvalidStepException) {
            return Status.INVALID_ARGUMENT;
        }
        if (error instanceof UnauthorizedException) {
            return Status.UNAUTHENTICATED;
        }
        if (error instanceof ForbiddenException) {
            return Status.PERMISSION_DENIED;
        }
        if (error instanceof ApplicationNotFoundException) {
            return Status.NOT_FOUND;
        }
        return Status.UNKNOWN;
    }

    private String errorCode(RuntimeException error) {
        if (error instanceof ApplicationRequiredException) {
            return "application_required";
        }
        if (error instanceof InvalidStepException) {
            return "invalid_step";
        }
        if (error instanceof UnauthorizedException) {
            return "unauthorized";
        }
        if (error instanceof ForbiddenException) {
            return "forbidden";
        }
        if (error instanceof ApplicationNotFoundException) {
            return "not_found";
        }
        return "unknown";
    }

    private final class GrpcDelegate extends OriginationDraftServiceGrpc.OriginationDraftServiceImplBase {
        @Override
        public void advanceApplicationStep(
                AdvanceApplicationStepRequest request,
                StreamObserver<AdvanceApplicationStepResponse> responseObserver) {
            OriginationDraftGrpcAdapter.this.advanceApplicationStep(request, responseObserver);
        }
    }
}
