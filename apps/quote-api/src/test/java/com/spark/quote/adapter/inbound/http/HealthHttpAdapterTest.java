package com.spark.quote.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.application.runtime.RuntimeDependencyProbe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthHttpAdapterTest {
    @Test
    void ready_whenDependencyIsDown_returnsServiceUnavailable() {
        HealthHttpAdapter adapter = new HealthHttpAdapter(List.of(() -> RuntimeDependencyProbe.Status.down("consul")));

        ResponseEntity<Map<String, Object>> response = adapter.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "NOT_READY");
        assertThat(response.getBody()).containsEntry("dependencies", Map.of("consul", "DOWN"));
    }
}
