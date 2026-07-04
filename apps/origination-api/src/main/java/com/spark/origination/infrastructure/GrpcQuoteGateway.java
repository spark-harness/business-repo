package com.spark.origination.infrastructure;

import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.domain.AcceptedQuote;
import com.vesta.lendora.quote.v1.GetQuoteRequest;
import com.vesta.lendora.quote.v1.Quote;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
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
    private static final TextMapSetter<Metadata> METADATA_SETTER = (metadata, key, value) -> {
        if (metadata != null && key != null && value != null) {
            metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
        }
    };

    private final QuoteServiceGrpc.QuoteServiceBlockingStub stub;
    private final Duration timeout;
    private final Tracer tracer;

    public GrpcQuoteGateway(QuoteServiceGrpc.QuoteServiceBlockingStub stub, Duration timeout) {
        this(stub, timeout, GlobalOpenTelemetry.get());
    }

    public GrpcQuoteGateway(
            QuoteServiceGrpc.QuoteServiceBlockingStub stub, Duration timeout, OpenTelemetry openTelemetry) {
        this.stub = stub;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        this.tracer = openTelemetry.getTracer("com.spark.origination.quote-grpc-client");
    }

    @Override
    public AcceptedQuote get(String quoteId) {
        String applicantId = RequestPrincipalContext.current()
                .orElseThrow(QuoteUnavailableException::new)
                .applicantId();
        Span span = tracer.spanBuilder("QuoteService/GetQuote")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.service", "vesta.lendora.quote.v1.QuoteService")
                .setAttribute("rpc.method", "GetQuote")
                .setAttribute("server.address", DEPENDENCY)
                .startSpan();
        long startedAt = System.nanoTime();
        try {
            Quote quote;
            Context clientContext = Context.current().with(span);
            try (Scope ignored = clientContext.makeCurrent()) {
                Metadata metadata = applicantMetadata(applicantId);
                W3CTraceContextPropagator.getInstance().inject(clientContext, metadata, METADATA_SETTER);
                quote = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                        .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .getQuote(GetQuoteRequest.newBuilder().setQuoteId(quoteId).build())
                        .getQuote();
            }
            if (quote == null) {
                markDependencyFailure(span, Status.Code.UNKNOWN, null);
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
            throw mapStatus(error, span, elapsedMillis(startedAt));
        } catch (RuntimeException error) {
            if (error instanceof QuoteNotFoundException
                    || error instanceof QuoteExpiredException
                    || error instanceof ForbiddenException
                    || error instanceof QuoteUnavailableException) {
                throw error;
            }
            logDependencyFailure(Status.Code.UNKNOWN, elapsedMillis(startedAt), error);
            markDependencyFailure(span, Status.Code.UNKNOWN, error);
            throw new QuoteUnavailableException(error);
        } finally {
            span.end();
        }
    }

    private static Metadata applicantMetadata(String applicantId) {
        Metadata metadata = new Metadata();
        metadata.put(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY, applicantId);
        return metadata;
    }

    private static RuntimeException mapStatus(StatusRuntimeException error, Span span, long latencyMillis) {
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
        markDependencyFailure(span, code, error);
        return new QuoteUnavailableException(error);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static void markDependencyFailure(Span span, Status.Code grpcStatus, Throwable error) {
        span.setStatus(StatusCode.ERROR, grpcStatus.name());
        span.setAttribute("error_code", ERROR_CODE);
        span.setAttribute("dependency", DEPENDENCY);
        span.setAttribute("rpc.grpc.status_code", grpcStatus.name());
        if (error != null) {
            span.recordException(error);
        }
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
