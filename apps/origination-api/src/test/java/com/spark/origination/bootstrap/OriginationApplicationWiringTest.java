package com.spark.origination.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spark.origination.consul.enabled=false")
class OriginationApplicationWiringTest {
    @Autowired
    private ObjectProvider<RuntimeDependencyProbe> runtimeDependencyProbes;

    @Test
    void applicationContext_whenDatabaseIsConfigured_shouldNotWireDatabaseHealthProbe() {
        assertThat(runtimeDependencyProbes.stream().toList())
                .extracting(probe -> probe.getClass().getSimpleName())
                .doesNotContain("JdbcRuntimeDependencyProbe");
    }
}
