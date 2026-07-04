package com.spark.origination.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.origination.application.AdvanceApplicationStepCommand;
import com.spark.origination.application.AdvanceApplicationStepUseCase;
import com.spark.origination.application.AmountOutOfRangeException;
import com.spark.origination.application.ApplicationNotFoundException;
import com.spark.origination.application.ApplicationRequiredException;
import com.spark.origination.application.CreateLoanApplicationCommand;
import com.spark.origination.application.CreateLoanApplicationUseCase;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.GetLoanApplicationUseCase;
import com.spark.origination.application.IdempotencyKeyConflictException;
import com.spark.origination.application.IdempotencyKeyRequiredException;
import com.spark.origination.application.InvalidStepException;
import com.spark.origination.application.PatchLoanApplicationCommand;
import com.spark.origination.application.PatchLoanApplicationUseCase;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.application.UnauthorizedException;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.ValidationException;
import com.vesta.lendora.origination.v1.CreateLoanApplicationRequest;
import com.vesta.lendora.origination.v1.CreateLoanApplicationResponse;
import com.vesta.lendora.origination.v1.GetLoanApplicationRequest;
import com.vesta.lendora.origination.v1.GetLoanApplicationResponse;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceGrpc;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceAdvanceApplicationStepRequest;
import com.vesta.lendora.origination.v1.OriginationLoanApplicationServiceAdvanceApplicationStepResponse;
import com.vesta.lendora.origination.v1.UpdateLoanApplicationRequest;
import com.vesta.lendora.origination.v1.UpdateLoanApplicationResponse;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;

@InboundAdapter
public class OriginationLoanApplicationGrpcAdapter implements BindableService {
    private final CreateLoanApplicationUseCase createUseCase;
    private final GetLoanApplicationUseCase getUseCase;
    private final PatchLoanApplicationUseCase patchUseCase;
    private final AdvanceApplicationStepUseCase advanceUseCase;
    private final OriginationLoanApplicationServiceGrpc.OriginationLoanApplicationServiceImplBase delegate =
            new GrpcDelegate();

    @Autowired
    public OriginationLoanApplicationGrpcAdapter(
            CreateLoanApplicationUseCase createUseCase,
            GetLoanApplicationUseCase getUseCase,
            PatchLoanApplicationUseCase patchUseCase,
            AdvanceApplicationStepUseCase advanceUseCase) {
        this.createUseCase = createUseCase;
        this.getUseCase = getUseCase;
        this.patchUseCase = patchUseCase;
        this.advanceUseCase = advanceUseCase;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return delegate.bindService();
    }

    private void createLoanApplication(
            CreateLoanApplicationRequest request,
            StreamObserver<CreateLoanApplicationResponse> responseObserver) {
        try {
            LoanApplication application = createUseCase.create(new CreateLoanApplicationCommand(
                    request.getProductCode(), toDomain(request.getLoan()), request.getQuoteId(), request.getIdempotencyKey()));
            responseObserver.onNext(CreateLoanApplicationResponse.newBuilder()
                    .setApplicationId(application.applicationId())
                    .setStatus(application.status().value())
                    .setCurrentStep(application.currentStep().value())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private void getLoanApplication(
            GetLoanApplicationRequest request,
            StreamObserver<GetLoanApplicationResponse> responseObserver) {
        try {
            LoanApplication application = getUseCase.get(request.getApplicationId());
            responseObserver.onNext(GetLoanApplicationResponse.newBuilder()
                    .setApplicationId(application.applicationId())
                    .setLoan(toProto(application.loan()))
                    .setAcceptedQuote(toProto(application.acceptedQuote()))
                    .setStatus(application.status().value())
                    .setCurrentStep(application.currentStep().value())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private void updateLoanApplication(
            UpdateLoanApplicationRequest request,
            StreamObserver<UpdateLoanApplicationResponse> responseObserver) {
        try {
            LoanApplication application = patchUseCase.patch(new PatchLoanApplicationCommand(
                    request.getApplicationId(), toDomain(request.getLoan()), request.getQuoteId(), request.getIdempotencyKey()));
            responseObserver.onNext(UpdateLoanApplicationResponse.newBuilder()
                    .setApplicationId(application.applicationId())
                    .setStatus(application.status().value())
                    .setCurrentStep(application.currentStep().value())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private void advanceApplicationStep(
            OriginationLoanApplicationServiceAdvanceApplicationStepRequest request,
            StreamObserver<OriginationLoanApplicationServiceAdvanceApplicationStepResponse> responseObserver) {
        try {
            LoanApplication application = advanceUseCase.advance(new AdvanceApplicationStepCommand(
                    request.getApplicationId(), toDomainStep(request.getTargetStep())));
            responseObserver.onNext(OriginationLoanApplicationServiceAdvanceApplicationStepResponse.newBuilder()
                    .setApplicationId(application.applicationId())
                    .setCurrentStep(toProtoStep(application.currentStep()))
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private static com.spark.origination.domain.LoanTerms toDomain(
            com.vesta.lendora.origination.v1.LoanTerms loan) {
        return new com.spark.origination.domain.LoanTerms(amount(loan.getAmount()), loan.getTerm(), loan.getPurpose());
    }

    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new ValidationException("loan amount is invalid");
        }
    }

    private static com.vesta.lendora.origination.v1.LoanTerms toProto(
            com.spark.origination.domain.LoanTerms loan) {
        return com.vesta.lendora.origination.v1.LoanTerms.newBuilder()
                .setAmount(loan.amount().toPlainString())
                .setTerm(loan.term())
                .setPurpose(loan.purpose())
                .build();
    }

    private static com.vesta.lendora.origination.v1.AcceptedQuote toProto(
            com.spark.origination.domain.AcceptedQuote quote) {
        return com.vesta.lendora.origination.v1.AcceptedQuote.newBuilder()
                .setQuoteId(quote.quoteId())
                .setMonthly(quote.monthly().toPlainString())
                .setApr(quote.apr().toPlainString())
                .setTotalInterest(quote.totalInterest().toPlainString())
                .setTotalPayable(quote.totalPayable().toPlainString())
                .setValidUntil(quote.validUntil().toString())
                .build();
    }

    private static com.spark.origination.domain.ApplicationStep toDomainStep(
            com.vesta.lendora.origination.v1.ApplicationStep step) {
        return switch (step) {
            case APPLICATION_STEP_IDENTITY_INFORMATION -> com.spark.origination.domain.ApplicationStep.IDENTITY_INFORMATION;
            default -> throw new InvalidStepException();
        };
    }

    private static com.vesta.lendora.origination.v1.ApplicationStep toProtoStep(
            com.spark.origination.domain.ApplicationStep step) {
        return switch (step) {
            case IDENTITY_INFORMATION -> com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_IDENTITY_INFORMATION;
            case LOAN_REQUEST -> com.vesta.lendora.origination.v1.ApplicationStep.APPLICATION_STEP_LOAN_REQUEST;
        };
    }

    private static Status toStatus(RuntimeException error) {
        if (error instanceof ApplicationRequiredException
                || error instanceof InvalidStepException
                || error instanceof IdempotencyKeyRequiredException
                || error instanceof ValidationException
                || error instanceof AmountOutOfRangeException) {
            return Status.INVALID_ARGUMENT;
        }
        if (error instanceof IdempotencyKeyConflictException) {
            return Status.ALREADY_EXISTS;
        }
        if (error instanceof UnauthorizedException) {
            return Status.UNAUTHENTICATED;
        }
        if (error instanceof ForbiddenException) {
            return Status.PERMISSION_DENIED;
        }
        if (error instanceof ApplicationNotFoundException || error instanceof QuoteNotFoundException) {
            return Status.NOT_FOUND;
        }
        if (error instanceof QuoteExpiredException) {
            return Status.FAILED_PRECONDITION;
        }
        if (error instanceof QuoteUnavailableException) {
            return Status.UNAVAILABLE;
        }
        return Status.UNKNOWN;
    }

    private static String errorCode(RuntimeException error) {
        if (error instanceof ApplicationRequiredException) {
            return "ORIGINATION-PARAM-0001";
        }
        if (error instanceof InvalidStepException) {
            return "ORIGINATION-PARAM-0001";
        }
        if (error instanceof IdempotencyKeyRequiredException) {
            return "ORIGINATION-PARAM-0001";
        }
        if (error instanceof AmountOutOfRangeException) {
            return "ORIGINATION-PARAM-0001";
        }
        if (error instanceof ValidationException) {
            return "ORIGINATION-PARAM-0001";
        }
        if (error instanceof IdempotencyKeyConflictException) {
            return "ORIGINATION-STATE-0002";
        }
        if (error instanceof UnauthorizedException) {
            return "ORIGINATION-AUTH-0001";
        }
        if (error instanceof ForbiddenException) {
            return "ORIGINATION-PERMISSION-0001";
        }
        if (error instanceof ApplicationNotFoundException) {
            return "ORIGINATION-STATE-0001";
        }
        if (error instanceof QuoteNotFoundException) {
            return "ORIGINATION-QUOTE-0001";
        }
        if (error instanceof QuoteExpiredException) {
            return "ORIGINATION-QUOTE-0002";
        }
        if (error instanceof QuoteUnavailableException) {
            return "ORIGINATION-QUOTE-0003";
        }
        return "ORIGINATION-SYSTEM-0001";
    }

    private final class GrpcDelegate
            extends OriginationLoanApplicationServiceGrpc.OriginationLoanApplicationServiceImplBase {
        @Override
        public void createLoanApplication(
                CreateLoanApplicationRequest request,
                StreamObserver<CreateLoanApplicationResponse> responseObserver) {
            OriginationLoanApplicationGrpcAdapter.this.createLoanApplication(request, responseObserver);
        }

        @Override
        public void getLoanApplication(
                GetLoanApplicationRequest request,
                StreamObserver<GetLoanApplicationResponse> responseObserver) {
            OriginationLoanApplicationGrpcAdapter.this.getLoanApplication(request, responseObserver);
        }

        @Override
        public void updateLoanApplication(
                UpdateLoanApplicationRequest request,
                StreamObserver<UpdateLoanApplicationResponse> responseObserver) {
            OriginationLoanApplicationGrpcAdapter.this.updateLoanApplication(request, responseObserver);
        }

        @Override
        public void advanceApplicationStep(
                OriginationLoanApplicationServiceAdvanceApplicationStepRequest request,
                StreamObserver<OriginationLoanApplicationServiceAdvanceApplicationStepResponse> responseObserver) {
            OriginationLoanApplicationGrpcAdapter.this.advanceApplicationStep(request, responseObserver);
        }
    }
}
