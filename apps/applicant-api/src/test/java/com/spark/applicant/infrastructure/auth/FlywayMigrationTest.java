package com.spark.applicant.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlywayMigrationTest {
    @Test
    void migrate_whenApplied_shouldCreateApplicantsSchemaWithVersionHistory() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer applicantColumnCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_name = 'APPLICANTS'
                """,
                Integer.class);
        Integer migrationCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from "flyway_schema_history"
                where "version" = '1'
                  and "description" = 'create applicants'
                  and "success" = true
                """,
                Integer.class);

        assertThat(applicantColumnCount).isEqualTo(6);
        assertThat(migrationCount).isEqualTo(1);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:h2:mem:applicant-flyway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
