package com.spark.quote.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.application.runtime.RuntimeDependencyProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "spark.quote.consul.enabled=false",
            "spark.quote.jdbc-url=jdbc:h2:mem:quote-wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spark.quote.jdbc-username=sa",
            "spark.quote.jdbc-password=",
            "spark.grpc.server.port=0"
        })
class QuoteApplicationWiringTest {
    @Autowired
    private ObjectProvider<RuntimeDependencyProbe> runtimeDependencyProbes;

    @Test
    void applicationContext_whenDatabaseIsConfigured_shouldNotWireDatabaseHealthProbe() {
        assertThat(runtimeDependencyProbes.stream().toList())
                .extracting(probe -> probe.getClass().getSimpleName())
                .doesNotContain("JdbcRuntimeDependencyProbe");
    }
}
