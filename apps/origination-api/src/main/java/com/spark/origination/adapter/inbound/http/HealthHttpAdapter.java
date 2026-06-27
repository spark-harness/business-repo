package com.spark.origination.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class HealthHttpAdapter {
    private final List<RuntimeDependencyProbe> dependencyProbes;

    public HealthHttpAdapter(List<RuntimeDependencyProbe> dependencyProbes) {
        this.dependencyProbes = List.copyOf(dependencyProbes);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "origination-api");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, String> dependencies = new LinkedHashMap<>();
        boolean ready = true;
        for (RuntimeDependencyProbe probe : dependencyProbes) {
            RuntimeDependencyProbe.Status status = probe.check();
            dependencies.put(status.name(), status.up() ? "UP" : "DOWN");
            ready = ready && status.up();
        }
        Map<String, Object> body = Map.of(
                "status", ready ? "READY" : "NOT_READY",
                "service", "origination-api",
                "dependencies", dependencies);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
