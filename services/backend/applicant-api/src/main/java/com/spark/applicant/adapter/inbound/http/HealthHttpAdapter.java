package com.spark.applicant.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class HealthHttpAdapter {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "applicant-api");
    }

    @GetMapping("/ready")
    public Map<String, String> ready() {
        return Map.of("status", "READY", "service", "applicant-api");
    }
}
