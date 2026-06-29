package com.spark.origination.infrastructure;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.origination.application.LoanApplicationRepository;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.ApplicationStatus;
import com.spark.origination.domain.ApplicationStep;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
public class JdbcLoanApplicationRepository implements LoanApplicationRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcLoanApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(LoanApplication application) {
        int updated = jdbcTemplate.update(
                """
                update loan_applications
                set applicant_id = ?, product_code = ?, status = ?, current_step = ?, amount = ?, term_months = ?,
                    purpose = ?, accepted_quote_id = ?, accepted_quote_snapshot = ?, created_at = ?, updated_at = ?
                where application_id = ?
                """,
                application.applicantId(),
                application.productCode(),
                application.status().value(),
                application.currentStep().value(),
                application.loan().amount(),
                application.loan().term(),
                application.loan().purpose(),
                application.acceptedQuote().quoteId(),
                QuoteSnapshotJson.write(application.acceptedQuote()),
                Timestamp.from(application.createdAt()),
                Timestamp.from(application.updatedAt()),
                application.applicationId());
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into loan_applications (
                  application_id, applicant_id, product_code, status, current_step, amount, term_months,
                  purpose, accepted_quote_id, accepted_quote_snapshot, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                application.applicationId(),
                application.applicantId(),
                application.productCode(),
                application.status().value(),
                application.currentStep().value(),
                application.loan().amount(),
                application.loan().term(),
                application.loan().purpose(),
                application.acceptedQuote().quoteId(),
                QuoteSnapshotJson.write(application.acceptedQuote()),
                Timestamp.from(application.createdAt()),
                Timestamp.from(application.updatedAt()));
    }

    @Override
    public Optional<LoanApplication> findById(String applicationId) {
        return jdbcTemplate
                .query(
                        "select * from loan_applications where application_id = ?",
                        (rs, rowNum) -> mapApplication(rs),
                        applicationId)
                .stream()
                .findFirst();
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("select count(*) from loan_applications", Long.class);
        return count == null ? 0 : count;
    }

    private static LoanApplication mapApplication(ResultSet rs) throws SQLException {
        AcceptedQuote quote = QuoteSnapshotJson.read(rs.getString("accepted_quote_snapshot"));
        return new LoanApplication(
                rs.getString("application_id"),
                rs.getString("applicant_id"),
                rs.getString("product_code"),
                new LoanTerms(rs.getBigDecimal("amount"), rs.getInt("term_months"), rs.getString("purpose")),
                quote,
                ApplicationStatus.DRAFT,
                ApplicationStep.fromValue(rs.getString("current_step")),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    static final class QuoteSnapshotJson {
        private QuoteSnapshotJson() {}

        static String write(AcceptedQuote quote) {
            return String.join(
                    "|",
                    quote.quoteId(),
                    quote.applicantId(),
                    quote.amount().toPlainString(),
                    Integer.toString(quote.term()),
                    quote.purpose(),
                    quote.monthly().toPlainString(),
                    quote.apr().toPlainString(),
                    quote.totalInterest().toPlainString(),
                    quote.totalPayable().toPlainString(),
                    quote.validUntil().toString());
        }

        static AcceptedQuote read(String value) {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 10) {
                throw new IllegalArgumentException("invalid quote snapshot");
            }
            return new AcceptedQuote(
                    parts[0],
                    parts[1],
                    new BigDecimal(parts[2]),
                    Integer.parseInt(parts[3]),
                    parts[4],
                    new BigDecimal(parts[5]),
                    new BigDecimal(parts[6]),
                    new BigDecimal(parts[7]),
                    new BigDecimal(parts[8]),
                    Instant.parse(parts[9]));
        }
    }
}
