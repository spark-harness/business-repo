package com.spark.common.spring.cleanarchitecture.transaction;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringTransactionRunner implements TransactionRunner {
    private final TransactionTemplate required;
    private final TransactionTemplate readOnly;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public <T> T required(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return required.execute(status -> action.get());
    }

    @Override
    public void required(Runnable action) {
        Objects.requireNonNull(action, "action");
        required(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T readOnly(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return readOnly.execute(status -> action.get());
    }

    @Override
    public void readOnly(Runnable action) {
        Objects.requireNonNull(action, "action");
        readOnly(() -> {
            action.run();
            return null;
        });
    }
}
