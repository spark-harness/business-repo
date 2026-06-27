package com.spark.quote.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.quote.application.CreateQuoteCommand;
import com.spark.quote.application.CreateQuoteUseCase;
import com.spark.quote.application.GetQuoteUseCase;
import com.spark.quote.domain.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class QuoteHttpAdapter {
    private final CreateQuoteUseCase createQuoteUseCase;
    private final GetQuoteUseCase getQuoteUseCase;

    public QuoteHttpAdapter(CreateQuoteUseCase createQuoteUseCase, GetQuoteUseCase getQuoteUseCase) {
        this.createQuoteUseCase = createQuoteUseCase;
        this.getQuoteUseCase = getQuoteUseCase;
    }

    @PostMapping("/api/v1/pricing/quotes")
    public CreateQuoteResponse create(
            @RequestHeader(name = "traceparent", required = false) String traceparent,
            @RequestBody CreateQuoteRequest request) {
        return CreateQuoteResponse.from(createQuoteUseCase.create(new CreateQuoteCommand(
                request.productCode(), request.amount(), request.term(), request.purpose(), traceId(traceparent))));
    }

    @GetMapping("/internal/v1/pricing/quotes/{quoteId}")
    public InternalQuoteResponse get(@PathVariable("quoteId") String quoteId) {
        return InternalQuoteResponse.from(getQuoteUseCase.get(quoteId));
    }

    public record CreateQuoteRequest(String productCode, BigDecimal amount, int term, String purpose) {}

    public record CreateQuoteResponse(
            String quoteId,
            String monthly,
            String apr,
            String totalInterest,
            String totalPayable,
            Instant validUntil) {
        static CreateQuoteResponse from(Quote quote) {
            return new CreateQuoteResponse(
                    quote.quoteId(),
                    quote.monthly().toPlainString(),
                    quote.apr().toPlainString(),
                    quote.totalInterest().toPlainString(),
                    quote.totalPayable().toPlainString(),
                    quote.validUntil());
        }
    }

    public record InternalQuoteResponse(
            String quoteId,
            String productCode,
            String amount,
            int term,
            String purpose,
            String monthly,
            String apr,
            String totalInterest,
            String totalPayable,
            Instant validUntil) {
        static InternalQuoteResponse from(Quote quote) {
            return new InternalQuoteResponse(
                    quote.quoteId(),
                    quote.productCode(),
                    quote.amount().toPlainString(),
                    quote.termMonths(),
                    quote.purpose(),
                    quote.monthly().toPlainString(),
                    quote.apr().toPlainString(),
                    quote.totalInterest().toPlainString(),
                    quote.totalPayable().toPlainString(),
                    quote.validUntil());
        }
    }

    private static String traceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return "";
        }
        String[] parts = traceparent.split("-");
        if (parts.length >= 4 && parts[1].length() == 32) {
            return parts[1];
        }
        return "";
    }
}
