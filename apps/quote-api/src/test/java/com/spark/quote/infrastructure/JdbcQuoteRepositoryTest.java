package com.spark.quote.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.domain.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcQuoteRepositoryTest {
    private JdbcQuoteRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:quote;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists quotes");
        jdbcTemplate.execute("""
                create table quotes (
                  quote_id varchar(80) primary key,
                  applicant_id varchar(80) not null,
                  product_code varchar(32) not null,
                  amount numeric(18,2) not null,
                  term_months integer not null,
                  purpose varchar(80) not null,
                  monthly numeric(18,2) not null,
                  apr numeric(8,4) not null,
                  total_interest numeric(18,2) not null,
                  total_payable numeric(18,2) not null,
                  valid_until timestamp not null,
                  created_at timestamp not null
                )
                """);
        repository = new JdbcQuoteRepository(jdbcTemplate);
    }

    @Test
    void saveAndFindById_roundTripsQuote() {
        Quote quote = new Quote(
                "quote_001",
                "applicant_001",
                "PIL",
                new BigDecimal("100000.00"),
                12,
                "debt_consolidation",
                new BigDecimal("8560.75"),
                new BigDecimal("0.0520"),
                new BigDecimal("2729.00"),
                new BigDecimal("102729.00"),
                Instant.parse("2026-06-28T00:30:00Z"),
                Instant.parse("2026-06-28T00:00:00Z"));

        repository.save(quote);

        assertThat(repository.findById("quote_001")).contains(quote);
    }
}
