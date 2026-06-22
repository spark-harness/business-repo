package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.ApplicantRepository;
import com.spark.applicant.domain.applicant.Applicant;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
public class JdbcApplicantRepository implements ApplicantRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public JdbcApplicantRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    JdbcApplicantRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    @Override
    public Applicant findOrCreateByPhoneNumber(PhoneNumber phoneNumber) {
        return findByPhoneKey(phoneNumber.stableKey()).orElseGet(() -> insertApplicant(phoneNumber));
    }

    private java.util.Optional<Applicant> findByPhoneKey(String phoneKey) {
        List<Applicant> applicants = jdbcTemplate.query(
                "select applicant_id, country_code, phone, created_at, updated_at from applicants where phone_key = ?",
                (resultSet, rowNum) -> mapApplicant(resultSet),
                phoneKey);
        return applicants.stream().findFirst();
    }

    private Applicant insertApplicant(PhoneNumber phoneNumber) {
        Instant now = clock.instant();
        Applicant applicant = new Applicant("applicant_" + UUID.randomUUID(), phoneNumber, now, now);
        try {
            jdbcTemplate.update(
                    """
                    insert into applicants (applicant_id, country_code, phone, phone_key, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    applicant.applicantId(),
                    phoneNumber.countryCode(),
                    phoneNumber.phone(),
                    phoneNumber.stableKey(),
                    Timestamp.from(applicant.createdAt()),
                    Timestamp.from(applicant.updatedAt()));
            return applicant;
        } catch (DuplicateKeyException ignored) {
            return findByPhoneKey(phoneNumber.stableKey()).orElseThrow();
        }
    }

    private Applicant mapApplicant(ResultSet resultSet) throws SQLException {
        return new Applicant(
                resultSet.getString("applicant_id"),
                new PhoneNumber(resultSet.getString("country_code"), resultSet.getString("phone")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

}
