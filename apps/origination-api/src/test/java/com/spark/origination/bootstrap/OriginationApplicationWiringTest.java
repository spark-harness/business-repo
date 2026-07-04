package com.spark.origination.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import io.grpc.BindableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spark.origination.consul.enabled=false", "spark.grpc.server.enabled=false"})
class OriginationApplicationWiringTest {
    @Autowired
    private ObjectProvider<RuntimeDependencyProbe> runtimeDependencyProbes;

    @Autowired
    private ObjectProvider<BindableService> bindableServices;

    @Test
    void applicationContext_whenDatabaseIsConfigured_shouldNotWireDatabaseHealthProbe() {
        assertThat(runtimeDependencyProbes.stream().toList())
                .extracting(probe -> probe.getClass().getSimpleName())
                .doesNotContain("JdbcRuntimeDependencyProbe");
    }

    @Test
    void applicationContext_whenGrpcAdaptersArePresent_shouldExposeOriginationGrpcServices() {
        assertThat(bindableServices.stream().toList())
                .extracting(service -> service.getClass().getSimpleName())
                .contains("OriginationDraftGrpcAdapter", "OriginationLoanApplicationGrpcAdapter");
    }
}
