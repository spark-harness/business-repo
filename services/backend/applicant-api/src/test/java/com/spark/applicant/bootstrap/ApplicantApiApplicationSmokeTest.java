package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=in-memory",
            "spark.applicant.auth.token-mode=simple",
            "spark.applicant.auth.consul.enabled=false"
        })
class ApplicantApiApplicationSmokeTest {
    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectProvider<DataSource> dataSource;

    @Test
    void healthEndpoint_whenApplicationStarts_shouldReportUp() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/actuator/health",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void applicationContext_whenDefaultRuntimeStoreIsInMemory_shouldNotCreateDataSource() {
        assertThat(dataSource.getIfAvailable()).isNull();
    }
}
