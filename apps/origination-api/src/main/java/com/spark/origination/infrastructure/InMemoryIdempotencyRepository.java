package com.spark.origination.infrastructure;

import com.spark.origination.application.IdempotencyKeyConflictException;
import com.spark.origination.application.IdempotencyRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryIdempotencyRepository implements IdempotencyRepository {
    private final Map<String, Record> records = new HashMap<>();

    @Override
    public Optional<String> findApplicationId(String applicantId, String operation, String key, String requestHash) {
        Record record = records.get(recordKey(applicantId, operation, key));
        if (record == null) {
            return Optional.empty();
        }
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException();
        }
        return Optional.of(record.applicationId());
    }

    @Override
    public void save(String applicantId, String operation, String key, String requestHash, String applicationId) {
        records.put(recordKey(applicantId, operation, key), new Record(requestHash, applicationId));
    }

    private static String recordKey(String applicantId, String operation, String key) {
        return applicantId + "|" + operation + "|" + key;
    }

    private record Record(String requestHash, String applicationId) {}
}
