package com.spark.applicant.adapter.inbound.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spark.applicant.application.runtime.RuntimeDependencyProbe;
import com.spark.applicant.bootstrap.ApplicantApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = {ApplicantApiApplication.class, ReadinessHttpAdapterTest.ProbeConfiguration.class},
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=in-memory",
            "spark.applicant.auth.token-mode=simple",
            "spark.applicant.auth.consul.enabled=false"
        })
@AutoConfigureMockMvc
class ReadinessHttpAdapterTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void ready_whenRuntimeDependenciesAreUp_shouldExposeDependencyStatuses() throws Exception {
        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.dependencies.postgresql").value("UP"))
                .andExpect(jsonPath("$.dependencies.redis").value("UP"))
                .andExpect(jsonPath("$.dependencies.consul").value("UP"));
    }

    @Test
    void ready_whenRuntimeDependencyIsDown_shouldReturnServiceUnavailable() throws Exception {
        ProbeConfiguration.redisUp = false;
        try {
            mockMvc.perform(get("/ready"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("NOT_READY"))
                    .andExpect(jsonPath("$.dependencies.redis").value("DOWN"));
        } finally {
            ProbeConfiguration.redisUp = true;
        }
    }

    static class ProbeConfiguration {
        private static boolean redisUp = true;

        @Bean
        RuntimeDependencyProbe postgresqlProbe() {
            return () -> RuntimeDependencyProbe.Status.up("postgresql");
        }

        @Bean
        RuntimeDependencyProbe redisProbe() {
            return () -> redisUp ? RuntimeDependencyProbe.Status.up("redis") : RuntimeDependencyProbe.Status.down("redis");
        }

        @Bean
        RuntimeDependencyProbe consulProbe() {
            return () -> RuntimeDependencyProbe.Status.up("consul");
        }
    }
}
