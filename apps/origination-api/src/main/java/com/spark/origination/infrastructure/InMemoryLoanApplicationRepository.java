package com.spark.origination.infrastructure;

import com.spark.origination.application.LoanApplicationRepository;
import com.spark.origination.domain.LoanApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryLoanApplicationRepository implements LoanApplicationRepository {
    private final Map<String, LoanApplication> applications = new LinkedHashMap<>();

    @Override
    public void save(LoanApplication application) {
        applications.put(application.applicationId(), application);
    }

    @Override
    public Optional<LoanApplication> findById(String applicationId) {
        return Optional.ofNullable(applications.get(applicationId));
    }

    @Override
    public long count() {
        return applications.size();
    }
}
