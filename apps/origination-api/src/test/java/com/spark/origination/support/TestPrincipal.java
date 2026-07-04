package com.spark.origination.support;

import com.spark.common.spring.security.RequestPrincipal;
import com.spark.common.spring.security.RequestPrincipalContext;
import java.lang.reflect.Method;

public final class TestPrincipal {
    private TestPrincipal() {}

    public static void set(String applicantId) {
        try {
            Method method = RequestPrincipalContext.class.getDeclaredMethod("set", RequestPrincipal.class);
            method.setAccessible(true);
            method.invoke(null, new RequestPrincipal(applicantId));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void clear() {
        try {
            Method method = RequestPrincipalContext.class.getDeclaredMethod("clear");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
