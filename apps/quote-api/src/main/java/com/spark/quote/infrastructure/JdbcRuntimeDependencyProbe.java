package com.spark.quote.infrastructure;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.quote.application.runtime.RuntimeDependencyProbe;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
public class JdbcRuntimeDependencyProbe implements RuntimeDependencyProbe {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRuntimeDependencyProbe(JdbcTemplate jdbcTemplate) {
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
