package com.spark.applicant.infrastructure.profile;

import com.spark.applicant.application.profile.IdentityProfileRepository;
import com.spark.applicant.domain.profile.IdentityProfile;
import com.spark.applicant.domain.profile.Nationality;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "runtime-store", havingValue = "redis-jdbc")
public class JdbcIdentityProfileRepository implements IdentityProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(IdentityProfile profile) {
        int updated = jdbcTemplate.update(
                """
                update applicant_identity_profiles
                set hkid_body = ?, hkid_check_digit = ?, first_name = ?, last_name = ?, chinese_name = ?,
                    nationality = ?, date_of_birth = ?, updated_at = ?
                where applicant_id = ?
                """,
                profile.hkidBody(),
                profile.hkidCheckDigit(),
                profile.firstName(),
                profile.lastName(),
                profile.chineseName(),
                profile.nationality().name(),
                profile.dateOfBirth(),
                Timestamp.from(profile.updatedAt()),
                profile.applicantId());
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into applicant_identity_profiles (
                  applicant_id, hkid_body, hkid_check_digit, first_name, last_name, chinese_name,
                  nationality, date_of_birth, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                profile.applicantId(),
                profile.hkidBody(),
                profile.hkidCheckDigit(),
                profile.firstName(),
                profile.lastName(),
                profile.chineseName(),
                profile.nationality().name(),
                profile.dateOfBirth(),
                Timestamp.from(profile.createdAt()),
                Timestamp.from(profile.updatedAt()));
    }

    @Override
    public Optional<IdentityProfile> findByApplicantId(String applicantId) {
        return jdbcTemplate
                .query(
                        "select * from applicant_identity_profiles where applicant_id = ?",
                        (rs, rowNum) -> mapProfile(rs),
                        applicantId)
                .stream()
                .findFirst();
    }

    private IdentityProfile mapProfile(ResultSet rs) throws SQLException {
        return new IdentityProfile(
                rs.getString("applicant_id"),
                rs.getString("hkid_body"),
                rs.getString("hkid_check_digit"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("chinese_name"),
                Nationality.valueOf(rs.getString("nationality")),
                rs.getString("date_of_birth"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
