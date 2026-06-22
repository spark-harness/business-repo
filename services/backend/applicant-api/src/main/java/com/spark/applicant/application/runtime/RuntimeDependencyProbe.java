package com.spark.applicant.application.runtime;

public interface RuntimeDependencyProbe {
    Status check();

    record Status(String name, boolean up) {
        public static Status up(String name) {
            return new Status(name, true);
        }

        public static Status down(String name) {
            return new Status(name, false);
        }
    }
}
