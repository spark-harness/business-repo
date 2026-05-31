package com.spark.user.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class HealthHttpAdapter {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "user-api");
    }
}
