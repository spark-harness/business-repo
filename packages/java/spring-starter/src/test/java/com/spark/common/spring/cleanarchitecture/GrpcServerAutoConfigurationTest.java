package com.spark.common.spring.cleanarchitecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.common.spring.cleanarchitecture.autoconfigure.GrpcServerAutoConfiguration;
import com.spark.common.spring.cleanarchitecture.grpc.GrpcServerLifecycle;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GrpcServerAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcServerAutoConfiguration.class));

    @Test
    void createsGrpcServerLifecycle_whenBindableServiceExists() {
        contextRunner
                .withBean(BindableService.class, TestBindableService::new)
                .withPropertyValues("spark.grpc.server.port=0")
                .run(context -> assertThat(context).hasSingleBean(GrpcServerLifecycle.class));
    }

    @Test
    void backsOff_whenGrpcServerDisabled() {
        contextRunner
                .withBean(BindableService.class, TestBindableService::new)
                .withPropertyValues("spark.grpc.server.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GrpcServerLifecycle.class));
    }

    private static class TestBindableService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder("spark.test.TestService").build();
        }
    }
}
