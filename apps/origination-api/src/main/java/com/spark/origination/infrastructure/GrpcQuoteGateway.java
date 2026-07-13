package com.spark.origination.infrastructure;

import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.domain.AcceptedQuote;
import com.vesta.lendora.quote.v1.GetQuoteRequest;
import com.vesta.lendora.quote.v1.Quote;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrpcQuoteGateway implements QuoteGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcQuoteGateway.class);
    private static final String DEPENDENCY = "quote-api";
    private static final String ERROR_CODE = "ORIGINATION-QUOTE-0003";

    private final QuoteServiceGrpc.QuoteServiceBlockingStub stub;
    private final Duration timeout;

    public GrpcQuoteGateway(QuoteServiceGrpc.QuoteServiceBlockingStub stub, Duration timeout) {
        this.stub = stub;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }

    @Override
    public AcceptedQuote get(String quoteId) {
        String applicantId = RequestPrincipalContext.current()
                .orElseThrow(QuoteUnavailableException::new)
                .applicantId();
        long startedAt = System.nanoTime();
        try {
            Quote quote = stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getQuote(GetQuoteRequest.newBuilder().setQuoteId(quoteId).build())
                    .getQuote();
            if (quote == null) {
                throw new QuoteUnavailableException("quote-api returned empty quote");
            }
            return new AcceptedQuote(
                    quote.getQuoteId(),
                    applicantId,
                    new BigDecimal(quote.getAmount()),
                    quote.getTerm(),
                    quote.getPurpose(),
                    new BigDecimal(quote.getMonthly()),
                    new BigDecimal(quote.getApr()),
                    new BigDecimal(quote.getTotalInterest()),
                    new BigDecimal(quote.getTotalPayable()),
                    Instant.parse(quote.getValidUntil()));
        } catch (StatusRuntimeException error) {
            throw mapStatus(error, elapsedMillis(startedAt));
        } catch (RuntimeException error) {
            if (error instanceof QuoteNotFoundException
                    || error instanceof QuoteExpiredException
                    || error instanceof ForbiddenException
                    || error instanceof QuoteUnavailableException) {
                throw error;
            }
            logDependencyFailure(Status.Code.UNKNOWN, elapsedMillis(startedAt), error);
            throw new QuoteUnavailableException(error);
        }
    }

    private static RuntimeException mapStatus(StatusRuntimeException error, long latencyMillis) {
        Status.Code code = error.getStatus().getCode();
        if (code == Status.Code.NOT_FOUND) {
            return new QuoteNotFoundException(error);
        }
        if (code == Status.Code.FAILED_PRECONDITION) {
            return new QuoteExpiredException(error);
        }
        if (code == Status.Code.PERMISSION_DENIED) {
            return new ForbiddenException(error);
        }
        logDependencyFailure(code, latencyMillis, error);
        return new QuoteUnavailableException(error);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static void logDependencyFailure(Status.Code grpcStatus, long latencyMillis, Throwable error) {
        LOGGER.warn(
                "quote dependency call failed error_code={} dependency={} grpc_status={} latency_ms={}",
                ERROR_CODE,
                DEPENDENCY,
                grpcStatus.name(),
                latencyMillis,
                error);
    }
}
