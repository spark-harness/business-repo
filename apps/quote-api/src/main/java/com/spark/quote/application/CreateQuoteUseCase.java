package com.spark.quote.application;

import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.quote.domain.PricingPolicy;
import com.spark.quote.domain.Quote;
import com.spark.quote.domain.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class CreateQuoteUseCase {
    private final QuoteRepository repository;
    private final Clock clock;
    private final PricingPolicy pricingPolicy;
    private final Duration validity;

    public CreateQuoteUseCase(QuoteRepository repository, Clock clock) {
        this(repository, clock, Duration.ofMinutes(30));
    }

    public CreateQuoteUseCase(QuoteRepository repository, Clock clock, Duration validity) {
        this.repository = repository;
        this.clock = clock;
        this.validity = validity;
        this.pricingPolicy = new PricingPolicy();
    }

    public Quote create(CreateQuoteCommand command) {
        validate(command);
        String applicantId = RequestPrincipalContext.current()
                .orElseThrow(UnauthorizedException::new)
                .applicantId();
        Instant createdAt = clock.instant();
        PricingPolicy.QuoteCalculation calculation =
                pricingPolicy.calculate(command.productCode(), command.amount(), command.term());
        Quote quote = new Quote(
                "quote_" + UUID.randomUUID(),
                applicantId,
                command.productCode(),
                command.amount(),
                command.term(),
                command.purpose(),
                calculation.monthly(),
                calculation.apr(),
                calculation.totalInterest(),
                calculation.totalPayable(),
                createdAt.plus(validity),
                createdAt);
        repository.save(quote);
        return quote;
    }

    private static void validate(CreateQuoteCommand command) {
        if (command == null
                || command.productCode() == null
                || command.productCode().isBlank()
                || command.amount() == null
                || command.purpose() == null
                || command.purpose().isBlank()) {
            throw new ValidationException("quote request is invalid");
        }
    }
}
