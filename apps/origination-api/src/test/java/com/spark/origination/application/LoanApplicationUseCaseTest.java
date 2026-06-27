package com.spark.origination.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.ApplicationStatus;
import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import com.spark.origination.infrastructure.InMemoryIdempotencyRepository;
import com.spark.origination.infrastructure.InMemoryLoanApplicationRepository;
import com.spark.origination.support.TestPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoanApplicationUseCaseTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryLoanApplicationRepository applications = new InMemoryLoanApplicationRepository();
    private final InMemoryIdempotencyRepository idempotency = new InMemoryIdempotencyRepository();
    private final FakeQuoteGateway quoteGateway = new FakeQuoteGateway();
    private final CreateLoanApplicationUseCase createUseCase =
            new CreateLoanApplicationUseCase(applications, idempotency, quoteGateway, clock);
    private final PatchLoanApplicationUseCase patchUseCase =
            new PatchLoanApplicationUseCase(applications, idempotency, quoteGateway, clock);
    private final GetLoanApplicationUseCase getUseCase = new GetLoanApplicationUseCase(applications);

    @Test
    void create_withValidQuote_createsDraftAndSnapshotsQuote() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");

        LoanApplication application = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        assertThat(application.applicationId()).startsWith("app_");
        assertThat(application.applicantId()).isEqualTo("applicant_001");
        assertThat(application.status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(application.currentStep()).isEqualTo(ApplicationStep.LOAN_REQUEST);
        assertThat(application.acceptedQuote().quoteId()).isEqualTo("quote_1");
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void create_withDifferentIdempotencyKeys_allowsMultipleDraftsForSameApplicant() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");

        LoanApplication first = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));
        quoteGateway.quote = quote("quote_2", "applicant_001", "120000.00", 24, "debt_consolidation");
        LoanApplication second = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("120000.00", 24, "debt_consolidation"), "quote_2", "idem-create-2"));

        assertThat(first.applicationId()).isNotEqualTo(second.applicationId());
        assertThat(applications.count()).isEqualTo(2);
    }

    @Test
    void create_withSameIdempotencyKey_replaysFirstApplication() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");

        LoanApplication first = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));
        LoanApplication second = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        assertThat(second.applicationId()).isEqualTo(first.applicationId());
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void create_withSameIdempotencyKeyAndDifferentRequest_rejectsConflict() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");
        createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        quoteGateway.quote = quote("quote_2", "applicant_001", "120000.00", 24, "debt_consolidation");

        assertThatThrownBy(() -> createUseCase.create(new CreateLoanApplicationCommand(
                        "PIL", loan("120000.00", 24, "debt_consolidation"), "quote_2", "idem-create-1")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void patch_withValidQuote_updatesLoanTermsWithoutAdvancingStep() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");
        LoanApplication application = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        quoteGateway.quote = quote("quote_2", "applicant_001", "120000.00", 24, "debt_consolidation");
        LoanApplication updated = patchUseCase.patch(new PatchLoanApplicationCommand(
                application.applicationId(), loan("120000.00", 24, "debt_consolidation"), "quote_2", "idem-patch-1"));

        assertThat(updated.status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(updated.currentStep()).isEqualTo(ApplicationStep.LOAN_REQUEST);
        assertThat(updated.loan().amount()).isEqualByComparingTo("120000.00");
        assertThat(updated.acceptedQuote().quoteId()).isEqualTo("quote_2");
    }

    @Test
    void get_withOwner_returnsSavedDraftForPrefill() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");
        LoanApplication application = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        LoanApplication got = getUseCase.get(application.applicationId());

        assertThat(got.loan().purpose()).isEqualTo("debt_consolidation");
        assertThat(got.acceptedQuote().monthly()).isEqualByComparingTo("8560.75");
    }

    @Test
    void get_withDifferentApplicant_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");
        LoanApplication application = createUseCase.create(new CreateLoanApplicationCommand(
                "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1"));

        TestPrincipal.set("applicant_002");

        assertThatThrownBy(() -> getUseCase.get(application.applicationId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void create_withQuoteOwnedByAnotherApplicant_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_002", "100000.00", 12, "debt_consolidation");

        assertThatThrownBy(() -> createUseCase.create(new CreateLoanApplicationCommand(
                        "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1")))
                .isInstanceOf(ForbiddenException.class);

        assertThat(applications.count()).isZero();
    }

    @Test
    void create_withExpiredQuote_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation")
                .withValidUntil(Instant.parse("2026-06-27T23:59:00Z"));

        assertThatThrownBy(() -> createUseCase.create(new CreateLoanApplicationCommand(
                        "PIL", loan("100000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1")))
                .isInstanceOf(QuoteExpiredException.class);
    }

    @Test
    void create_withLoanTermsDifferentFromQuote_rejectsAccess() {
        TestPrincipal.set("applicant_001");
        quoteGateway.quote = quote("quote_1", "applicant_001", "100000.00", 12, "debt_consolidation");

        assertThatThrownBy(() -> createUseCase.create(new CreateLoanApplicationCommand(
                        "PIL", loan("120000.00", 12, "debt_consolidation"), "quote_1", "idem-create-1")))
                .isInstanceOf(AmountOutOfRangeException.class);
    }

    private static LoanTerms loan(String amount, int term, String purpose) {
        return new LoanTerms(new BigDecimal(amount), term, purpose);
    }

    private static AcceptedQuote quote(String quoteId, String applicantId, String amount, int term, String purpose) {
        return new AcceptedQuote(
                quoteId,
                applicantId,
                new BigDecimal(amount),
                term,
                purpose,
                new BigDecimal("8560.75"),
                new BigDecimal("0.0520"),
                new BigDecimal("2729.00"),
                new BigDecimal("102729.00"),
                Instant.parse("2026-06-28T00:30:00Z"));
    }

    private static class FakeQuoteGateway implements QuoteGateway {
        private AcceptedQuote quote;

        @Override
        public AcceptedQuote get(String quoteId) {
            if (quote == null || !quote.quoteId().equals(quoteId)) {
                throw new QuoteNotFoundException();
            }
            return quote;
        }
    }
}
