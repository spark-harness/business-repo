package com.spark.quote.application;

import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.quote.domain.Quote;
import java.time.Clock;

public class GetQuoteUseCase {
    private final QuoteRepository repository;
    private final Clock clock;

    public GetQuoteUseCase(QuoteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Quote get(String quoteId) {
        String applicantId = RequestPrincipalContext.current()
                .orElseThrow(UnauthorizedException::new)
                .applicantId();
        Quote quote = repository.findById(quoteId).orElseThrow(() -> new QuoteNotFoundException(quoteId));
        if (!quote.applicantId().equals(applicantId)) {
            throw new QuoteForbiddenException();
        }
        if (!quote.validUntil().isAfter(clock.instant())) {
            throw new QuoteExpiredException(quoteId);
        }
        return quote;
    }
}
