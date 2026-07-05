package com.spark.quote.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.quote.domain.AmountOutOfRangeException;
import com.spark.quote.domain.Quote;
import com.spark.quote.infrastructure.InMemoryQuoteRepository;
import com.spark.quote.support.TestPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QuoteUseCaseTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryQuoteRepository repository = new InMemoryQuoteRepository();
    private final CreateQuoteUseCase createQuoteUseCase = new CreateQuoteUseCase(repository, clock);
    private final GetQuoteUseCase getQuoteUseCase = new GetQuoteUseCase(repository, clock);

    @Test
    void createQuote_withValidPilRequest_persistsQuoteAndReturnsPricing() {
        TestPrincipal.set("applicant_001");

        Quote quote = createQuoteUseCase.create(new CreateQuoteCommand(
                "PIL", new BigDecimal("100000.00"), 12, "debt_consolidation"));

        assertThat(quote.quoteId()).startsWith("quote_");
        assertThat(quote.applicantId()).isEqualTo("applicant_001");
        assertThat(quote.monthly()).isEqualByComparingTo("8560.75");
        assertThat(quote.apr()).isEqualByComparingTo("0.0520");
        assertThat(quote.totalInterest()).isEqualByComparingTo("2729.00");
        assertThat(quote.totalPayable()).isEqualByComparingTo("102729.00");
        assertThat(quote.validUntil()).isEqualTo(Instant.parse("2026-06-28T00:30:00Z"));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void createQuote_withOutOfRangeAmount_doesNotPersistQuote() {
        TestPrincipal.set("applicant_001");

        assertThatThrownBy(() -> createQuoteUseCase.create(new CreateQuoteCommand(
                        "PIL", new BigDecimal("4999.99"), 12, "debt_consolidation")))
                .isInstanceOf(AmountOutOfRangeException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    void getQuote_withDifferentApplicant_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        Quote quote = createQuoteUseCase.create(new CreateQuoteCommand(
                "PIL", new BigDecimal("100000.00"), 12, "debt_consolidation"));

        TestPrincipal.set("applicant_002");

        assertThatThrownBy(() -> getQuoteUseCase.get(quote.quoteId()))
                .isInstanceOf(QuoteForbiddenException.class);
    }

    @Test
    void getQuote_withExpiredQuote_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        Quote quote = createQuoteUseCase.create(new CreateQuoteCommand(
                "PIL", new BigDecimal("100000.00"), 12, "debt_consolidation"));
        GetQuoteUseCase expiredUseCase = new GetQuoteUseCase(
                repository,
                Clock.fixed(Instant.parse("2026-06-28T00:31:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredUseCase.get(quote.quoteId()))
                .isInstanceOf(QuoteExpiredException.class);
    }
}
