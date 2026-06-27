package com.spark.origination.application;

import com.spark.origination.domain.LoanApplication;
import java.util.Optional;

public interface LoanApplicationRepository {
    void save(LoanApplication application);

    Optional<LoanApplication> findById(String applicationId);

    long count();
}
