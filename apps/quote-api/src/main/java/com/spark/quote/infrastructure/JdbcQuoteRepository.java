package com.spark.quote.infrastructure;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.quote.application.QuoteRepository;
import com.spark.quote.domain.Quote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
public class JdbcQuoteRepository implements QuoteRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcQuoteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(Quote quote) {
        jdbcTemplate.update(
                """
                insert into quotes (
                  quote_id, applicant_id, product_code, amount, term_months, purpose,
                  monthly, apr, total_interest, total_payable, valid_until, trace_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                quote.quoteId(),
                quote.applicantId(),
                quote.productCode(),
                quote.amount(),
                quote.termMonths(),
                quote.purpose(),
                quote.monthly(),
                quote.apr(),
                quote.totalInterest(),
                quote.totalPayable(),
                Timestamp.from(quote.validUntil()),
                quote.traceId(),
                Timestamp.from(quote.createdAt()));
    }

    @Override
    public Optional<Quote> findById(String quoteId) {
        return jdbcTemplate
                .query(
                        "select * from quotes where quote_id = ?",
                        (rs, rowNum) -> mapQuote(rs),
                        quoteId)
                .stream()
                .findFirst();
    }

    private static Quote mapQuote(ResultSet rs) throws SQLException {
        return new Quote(
                rs.getString("quote_id"),
                rs.getString("applicant_id"),
                rs.getString("product_code"),
                rs.getBigDecimal("amount"),
                rs.getInt("term_months"),
                rs.getString("purpose"),
                rs.getBigDecimal("monthly"),
                rs.getBigDecimal("apr"),
                rs.getBigDecimal("total_interest"),
                rs.getBigDecimal("total_payable"),
                toInstant(rs, "valid_until"),
                rs.getString("trace_id"),
                toInstant(rs, "created_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
