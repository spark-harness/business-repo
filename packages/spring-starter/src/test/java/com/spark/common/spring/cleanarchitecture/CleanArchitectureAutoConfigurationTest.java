package com.spark.common.spring.cleanarchitecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.common.spring.cleanarchitecture.autoconfigure.CleanArchitectureAutoConfiguration;
import com.spark.common.spring.cleanarchitecture.transaction.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class CleanArchitectureAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CleanArchitectureAutoConfiguration.class));

    @Test
    void createsTransactionRunner_whenTransactionManagerExists() {
        contextRunner.withBean(RecordingTransactionManager.class).run(context -> {
            assertThat(context).hasSingleBean(TransactionRunner.class);

            TransactionRunner runner = context.getBean(TransactionRunner.class);
            RecordingTransactionManager transactionManager = context.getBean(RecordingTransactionManager.class);

            assertThat(runner.required(() -> "created")).isEqualTo("created");
            assertThat(transactionManager.beginCount).isEqualTo(1);
            assertThat(transactionManager.commitCount).isEqualTo(1);
            assertThat(transactionManager.lastReadOnly).isFalse();

            assertThat(runner.readOnly(() -> "loaded")).isEqualTo("loaded");
            assertThat(transactionManager.beginCount).isEqualTo(2);
            assertThat(transactionManager.commitCount).isEqualTo(2);
            assertThat(transactionManager.lastReadOnly).isTrue();
        });
    }

    @Test
    void backsOff_whenCustomTransactionRunnerExists() {
        contextRunner
                .withBean(RecordingTransactionManager.class)
                .withBean(TransactionRunner.class, StubTransactionRunner::new)
                .run(context -> assertThat(context).hasSingleBean(TransactionRunner.class)
                        .getBean(TransactionRunner.class)
                        .isInstanceOf(StubTransactionRunner.class));
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int beginCount;
        int commitCount;
        boolean lastReadOnly;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            beginCount++;
            lastReadOnly = definition.isReadOnly();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    static final class StubTransactionRunner implements TransactionRunner {
        @Override
        public <T> T required(java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public void required(Runnable action) {
            action.run();
        }

        @Override
        public <T> T readOnly(java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public void readOnly(Runnable action) {
            action.run();
        }
    }
}
