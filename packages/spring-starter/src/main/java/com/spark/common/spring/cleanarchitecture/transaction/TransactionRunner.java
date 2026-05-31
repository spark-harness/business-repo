package com.spark.common.spring.cleanarchitecture.transaction;

import java.util.function.Supplier;

public interface TransactionRunner {
    <T> T required(Supplier<T> action);

    void required(Runnable action);

    <T> T readOnly(Supplier<T> action);

    void readOnly(Runnable action);
}
