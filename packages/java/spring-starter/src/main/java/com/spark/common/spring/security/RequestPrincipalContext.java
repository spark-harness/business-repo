package com.spark.common.spring.security;

import java.util.Optional;

public final class RequestPrincipalContext {
    private static final ThreadLocal<RequestPrincipal> CURRENT = new ThreadLocal<>();

    private RequestPrincipalContext() {}

    public static Optional<RequestPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static RequestPrincipal required() {
        return current().orElseThrow(() -> new IllegalStateException("request principal is required"));
    }

    static void set(RequestPrincipal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }
}
