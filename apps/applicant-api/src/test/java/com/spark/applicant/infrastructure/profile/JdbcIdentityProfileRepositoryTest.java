package com.spark.applicant.infrastructure.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.applicant.domain.profile.IdentityProfile;
import com.spark.applicant.domain.profile.Nationality;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcIdentityProfileRepositoryTest {
    @Test
    void save_whenProfileExists_shouldPersistClearTextFieldsAndOverwriteByApplicant() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcIdentityProfileRepository repository = new JdbcIdentityProfileRepository(jdbcTemplate);
        jdbcTemplate.update(
                """
                insert into applicants (applicant_id, country_code, phone, phone_key, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                "applicant_001",
                "+852",
                "91234567",
                "+852:91234567",
                java.sql.Timestamp.from(Instant.parse("2026-06-28T01:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-06-28T01:00:00Z")));

        repository.save(profile("Ada", Instant.parse("2026-06-28T01:00:00Z")));
        repository.save(profile("Grace", Instant.parse("2026-06-28T02:00:00Z")));

        assertThat(repository.findByApplicantId("applicant_001")).hasValueSatisfying(profile -> {
            assertThat(profile.firstName()).isEqualTo("Grace");
            assertThat(profile.hkidBody()).isEqualTo("A123456");
            assertThat(profile.hkidCheckDigit()).isEqualTo("3");
            assertThat(profile.nationality()).isEqualTo(Nationality.HONG_KONG);
        });
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from applicant_identity_profiles where applicant_id = ?",
                        Long.class,
                        "applicant_001"))
                .isEqualTo(1L);
    }

    private IdentityProfile profile(String firstName, Instant updatedAt) {
        return new IdentityProfile(
                "applicant_001",
                "A123456",
                "3",
                firstName,
                "Lovelace",
                "陳小明",
                Nationality.HONG_KONG,
                "1990-01-15",
                Instant.parse("2026-06-28T01:00:00Z"),
                updatedAt);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:h2:mem:identity-profile-repo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
