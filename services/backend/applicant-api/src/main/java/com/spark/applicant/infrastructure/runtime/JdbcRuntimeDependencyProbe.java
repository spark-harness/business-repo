package com.spark.applicant.infrastructure.runtime;

import com.spark.applicant.application.runtime.RuntimeDependencyProbe;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
@ConditionalOnBean(JdbcTemplate.class)
class JdbcRuntimeDependencyProbe implements RuntimeDependencyProbe {
    private final JdbcTemplate jdbcTemplate;

    JdbcRuntimeDependencyProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Status check() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return Status.up("postgresql");
        } catch (RuntimeException error) {
            return Status.down("postgresql");
        }
    }
}
