package com.spark.quote.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.quote.application.CreateQuoteCommand;
import com.spark.quote.application.CreateQuoteUseCase;
import com.spark.quote.application.GetQuoteUseCase;
import com.spark.quote.application.QuoteExpiredException;
import com.spark.quote.application.QuoteForbiddenException;
import com.spark.quote.application.QuoteNotFoundException;
import com.spark.quote.application.UnauthorizedException;
import com.spark.quote.domain.AmountOutOfRangeException;
import com.spark.quote.domain.ValidationException;
import com.vesta.lendora.quote.v1.CreateQuoteRequest;
import com.vesta.lendora.quote.v1.CreateQuoteResponse;
import com.vesta.lendora.quote.v1.GetQuoteRequest;
import com.vesta.lendora.quote.v1.GetQuoteResponse;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@InboundAdapter
public class QuoteGrpcAdapter implements BindableService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteGrpcAdapter.class);

    private final CreateQuoteUseCase createQuoteUseCase;
    private final GetQuoteUseCase getQuoteUseCase;
    private final QuoteServiceGrpc.QuoteServiceImplBase delegate = new GrpcDelegate();

    @Autowired
    public QuoteGrpcAdapter(CreateQuoteUseCase createQuoteUseCase, GetQuoteUseCase getQuoteUseCase) {
        this.createQuoteUseCase = createQuoteUseCase;
        this.getQuoteUseCase = getQuoteUseCase;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return delegate.bindService();
    }

    private void createQuote(CreateQuoteRequest request, StreamObserver<CreateQuoteResponse> responseObserver) {
        try {
            com.spark.quote.domain.Quote quote = createQuoteUseCase.create(new CreateQuoteCommand(
                    request.getProductCode(),
                    amount(request.getAmount()),
                    request.getTerm(),
                    request.getPurpose()));
            logSuccess("QuoteService/CreateQuote");
            responseObserver.onNext(CreateQuoteResponse.newBuilder()
                    .setQuote(toProto(quote))
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            logFailure("QuoteService/CreateQuote", errorCode(error), error);
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private void getQuote(GetQuoteRequest request, StreamObserver<GetQuoteResponse> responseObserver) {
        try {
            com.spark.quote.domain.Quote quote = getQuoteUseCase.get(request.getQuoteId());
            logSuccess("QuoteService/GetQuote");
            responseObserver.onNext(GetQuoteResponse.newBuilder()
                    .setQuote(toProto(quote))
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException error) {
            logFailure("QuoteService/GetQuote", errorCode(error), error);
            responseObserver.onError(toStatus(error).withDescription(errorCode(error)).asRuntimeException());
        }
    }

    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new ValidationException("quote amount is invalid");
        }
    }

    private static com.vesta.lendora.quote.v1.Quote toProto(com.spark.quote.domain.Quote quote) {
        return com.vesta.lendora.quote.v1.Quote.newBuilder()
                .setQuoteId(quote.quoteId())
                .setProductCode(quote.productCode())
                .setAmount(quote.amount().toPlainString())
                .setTerm(quote.termMonths())
                .setPurpose(quote.purpose())
                .setMonthly(quote.monthly().toPlainString())
                .setApr(quote.apr().toPlainString())
                .setTotalInterest(quote.totalInterest().toPlainString())
                .setTotalPayable(quote.totalPayable().toPlainString())
                .setValidUntil(quote.validUntil().toString())
                .build();
    }

    private static Status toStatus(RuntimeException error) {
        if (error instanceof ValidationException || error instanceof AmountOutOfRangeException) {
            return Status.INVALID_ARGUMENT;
        }
        if (error instanceof UnauthorizedException) {
            return Status.UNAUTHENTICATED;
        }
        if (error instanceof QuoteForbiddenException) {
            return Status.PERMISSION_DENIED;
        }
        if (error instanceof QuoteNotFoundException) {
            return Status.NOT_FOUND;
        }
        if (error instanceof QuoteExpiredException) {
            return Status.FAILED_PRECONDITION;
        }
        return Status.UNKNOWN;
    }

    private static String errorCode(RuntimeException error) {
        if (error instanceof AmountOutOfRangeException) {
            return "QUOTE-PARAM-0002";
        }
        if (error instanceof ValidationException) {
            return "QUOTE-PARAM-0001";
        }
        if (error instanceof UnauthorizedException) {
            return "QUOTE-AUTH-0001";
        }
        if (error instanceof QuoteForbiddenException) {
            return "QUOTE-PERMISSION-0001";
        }
        if (error instanceof QuoteNotFoundException) {
            return "QUOTE-STATE-0001";
        }
        if (error instanceof QuoteExpiredException) {
            return "QUOTE-STATE-0002";
        }
        return "QUOTE-SYSTEM-0001";
    }

    private static void logSuccess(String operation) {
        SpanContext spanContext = Span.current().getSpanContext();
        LOGGER.info(
                "service=quote-api operation={} result=success trace_id={} span_id={}",
                operation,
                spanContext.getTraceId(),
                spanContext.getSpanId());
    }

    private static void logFailure(String operation, String errorCode, RuntimeException error) {
        SpanContext spanContext = Span.current().getSpanContext();
        LOGGER.warn(
                "service=quote-api operation={} result=failure error_code={} trace_id={} span_id={}",
                operation,
                errorCode,
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                error);
    }

    private final class GrpcDelegate extends QuoteServiceGrpc.QuoteServiceImplBase {
        @Override
        public void createQuote(CreateQuoteRequest request, StreamObserver<CreateQuoteResponse> responseObserver) {
            QuoteGrpcAdapter.this.createQuote(request, responseObserver);
        }

        @Override
        public void getQuote(GetQuoteRequest request, StreamObserver<GetQuoteResponse> responseObserver) {
            QuoteGrpcAdapter.this.getQuote(request, responseObserver);
        }
    }
}
