package com.spark.origination.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class HealthHttpAdapter {
    private final RuntimeDependencyProbe dependencyProbe;

    public HealthHttpAdapter(RuntimeDependencyProbe dependencyProbe) {
        this.dependencyProbe = dependencyProbe;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        dependencyProbe.checkReady();
        return ResponseEntity.ok(Map.of("status", "READY"));
    }
}
