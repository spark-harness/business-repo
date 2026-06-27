package com.spark.origination.infrastructure;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.origination.application.IdempotencyKeyConflictException;
import com.spark.origination.application.IdempotencyRepository;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
public class JdbcIdempotencyRepository implements IdempotencyRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findApplicationId(String applicantId, String operation, String key, String requestHash) {
        return jdbcTemplate
                .query(
                        """
                        select request_hash, application_id from idempotency_records
                        where applicant_id = ? and operation = ? and idempotency_key = ?
                        """,
                        (rs, rowNum) -> new Record(rs.getString("request_hash"), rs.getString("application_id")),
                        applicantId,
                        operation,
                        key)
                .stream()
                .findFirst()
                .map(record -> {
                    if (!record.requestHash().equals(requestHash)) {
                        throw new IdempotencyKeyConflictException();
                    }
                    return record.applicationId();
                });
    }

    @Override
    public void save(String applicantId, String operation, String key, String requestHash, String applicationId) {
        jdbcTemplate.update(
                """
                insert into idempotency_records (
                  applicant_id, operation, idempotency_key, request_hash, application_id
                ) values (?, ?, ?, ?, ?)
                """,
                applicantId,
                operation,
                key,
                requestHash,
                applicationId);
    }

    private record Record(String requestHash, String applicationId) {}
}
