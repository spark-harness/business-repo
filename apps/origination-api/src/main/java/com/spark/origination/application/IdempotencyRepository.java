package com.spark.origination.application;

import java.util.Optional;

public interface IdempotencyRepository {
    Optional<String> findApplicationId(String applicantId, String operation, String key, String requestHash);

    void save(String applicantId, String operation, String key, String requestHash, String applicationId);
}
