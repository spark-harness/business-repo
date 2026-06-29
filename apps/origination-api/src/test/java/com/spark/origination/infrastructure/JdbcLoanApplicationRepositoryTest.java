package com.spark.origination.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.origination.application.IdempotencyKeyConflictException;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.ApplicationStatus;
import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import java.math.BigDecimal;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcLoanApplicationRepositoryTest {
    private JdbcLoanApplicationRepository applications;
    private JdbcIdempotencyRepository idempotency;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:origination-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        applications = new JdbcLoanApplicationRepository(jdbcTemplate);
        idempotency = new JdbcIdempotencyRepository(jdbcTemplate);
    }

    @Test
    void saveAndFindById_roundTripsLoanApplication() {
        LoanApplication application = application("app_1", "100000.00", 12, "quote_1");

        applications.save(application);

        LoanApplication found = applications.findById("app_1").orElseThrow();
        assertThat(found.applicationId()).isEqualTo("app_1");
        assertThat(found.applicantId()).isEqualTo("applicant_001");
        assertThat(found.loan().amount()).isEqualByComparingTo("100000.00");
        assertThat(found.acceptedQuote().quoteId()).isEqualTo("quote_1");
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void save_withExistingApplication_updatesLoanAndQuote() {
        applications.save(application("app_1", "100000.00", 12, "quote_1"));
        applications.save(application("app_1", "120000.00", 24, "quote_2"));

        LoanApplication found = applications.findById("app_1").orElseThrow();
        assertThat(found.loan().amount()).isEqualByComparingTo("120000.00");
        assertThat(found.loan().term()).isEqualTo(24);
        assertThat(found.acceptedQuote().quoteId()).isEqualTo("quote_2");
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void findById_whenCurrentStepIsIdentityInformation_shouldReadStoredStep() {
        LoanApplication application = application("app_1", "100000.00", 12, "quote_1")
                .advanceTo(ApplicationStep.IDENTITY_INFORMATION, Instant.parse("2026-06-28T02:00:00Z"));

        applications.save(application);

        assertThat(applications.findById("app_1")).hasValueSatisfying(stored ->
                assertThat(stored.currentStep()).isEqualTo(ApplicationStep.IDENTITY_INFORMATION));
    }

    @Test
    void idempotencyRecord_roundTripsApplicationId() {
        idempotency.save("applicant_001", "create", "idem-1", "hash-1", "app_1");

        assertThat(idempotency.findApplicationId("applicant_001", "create", "idem-1", "hash-1"))
                .contains("app_1");
        assertThatThrownBy(() -> idempotency.findApplicationId("applicant_001", "create", "idem-1", "hash-2"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    private static LoanApplication application(String applicationId, String amount, int term, String quoteId) {
        Instant now = Instant.parse("2026-06-28T00:00:00Z");
        return new LoanApplication(
                applicationId,
                "applicant_001",
                "PIL",
                new LoanTerms(new BigDecimal(amount), term, "debt_consolidation"),
                new AcceptedQuote(
                        quoteId,
                        "applicant_001",
                        new BigDecimal(amount),
                        term,
                        "debt_consolidation",
                        new BigDecimal("8560.75"),
                        new BigDecimal("0.0520"),
                        new BigDecimal("2729.00"),
                        new BigDecimal("102729.00"),
                        Instant.parse("2026-06-28T00:30:00Z")),
                ApplicationStatus.DRAFT,
                ApplicationStep.LOAN_REQUEST,
                now,
                now);
    }
}
