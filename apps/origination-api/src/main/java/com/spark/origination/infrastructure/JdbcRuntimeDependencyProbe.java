package com.spark.origination.infrastructure;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import org.springframework.jdbc.core.JdbcTemplate;

@InfrastructureAdapter
public class JdbcRuntimeDependencyProbe implements RuntimeDependencyProbe {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRuntimeDependencyProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void checkReady() {
        jdbcTemplate.queryForObject("select 1", Integer.class);
    }
}
