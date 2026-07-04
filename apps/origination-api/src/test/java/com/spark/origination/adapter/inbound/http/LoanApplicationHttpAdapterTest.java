package com.spark.origination.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.bootstrap.OriginationApiApplication;
import com.spark.origination.domain.AcceptedQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = {OriginationApiApplication.class, LoanApplicationHttpAdapterTest.QuoteGatewayTestConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spark.grpc.server.enabled=false")
class LoanApplicationHttpAdapterTest {
    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spark.origination.jdbc-url",
                () -> "jdbc:h2:mem:origination-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Test
    void ready_withDatabaseAvailable_returnsReady() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/ready"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "READY");
    }

    @Test
    void create_withoutIdempotencyKey_returnsStableError() {
        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/v1/loan-applications"),
                HttpMethod.POST,
                entity(Map.of(
                        "productCode", "PIL",
                        "loan", Map.of("amount", "100000.00", "term", 12, "purpose", "debt_consolidation"),
                        "quoteId", "quote_1"), null, "applicant_001"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "idempotency_key_required");
    }

    @Test
    void createGetAndPatch_withValidQuote_persistsDraftForPrefill() {
        ResponseEntity<Map> create = restTemplate.exchange(
                url("/api/v1/loan-applications"),
                HttpMethod.POST,
                entity(Map.of(
                        "productCode", "PIL",
                        "loan", Map.of("amount", "100000.00", "term", 12, "purpose", "debt_consolidation"),
                        "quoteId", "quote_1"), "idem-create-http", "applicant_001"),
                Map.class);

        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(create.getBody()).containsEntry("status", "draft").containsEntry("currentStep", "loan_request");
        String applicationId = (String) create.getBody().get("applicationId");

        ResponseEntity<Map> patch = restTemplate.exchange(
                url("/api/v1/loan-applications/" + applicationId),
                HttpMethod.PATCH,
                entity(Map.of(
                        "loan", Map.of("amount", "120000.00", "term", 24, "purpose", "debt_consolidation"),
                        "quoteId", "quote_2"), "idem-patch-http", "applicant_001"),
                Map.class);

        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getBody()).containsEntry("applicationId", applicationId).containsEntry("currentStep", "loan_request");

        ResponseEntity<Map> get = restTemplate.exchange(
                url("/api/v1/loan-applications/" + applicationId),
                HttpMethod.GET,
                entity(Map.of(), null, "applicant_001"),
                Map.class);

        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).containsEntry("applicationId", applicationId);
        Map<?, ?> loan = (Map<?, ?>) get.getBody().get("loan");
        assertThat(loan.get("amount")).isEqualTo("120000.00");
        assertThat(loan.get("term")).isEqualTo(24);
        assertThat(loan.get("purpose")).isEqualTo("debt_consolidation");
        Map<?, ?> acceptedQuote = (Map<?, ?>) get.getBody().get("acceptedQuote");
        assertThat(acceptedQuote.get("quoteId")).isEqualTo("quote_2");
    }

    @Test
    void create_withExpiredQuote_returnsQuoteExpired() {
        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/v1/loan-applications"),
                HttpMethod.POST,
                entity(Map.of(
                        "productCode", "PIL",
                        "loan", Map.of("amount", "100000.00", "term", 12, "purpose", "debt_consolidation"),
                        "quoteId", "quote_expired"), "idem-expired-http", "applicant_001"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("error", "quote_expired");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpEntity<Map<String, Object>> entity(
            Map<String, Object> body, String idempotencyKey, String applicantId) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        if (applicantId != null) {
            headers.add("x-applicant-id", applicantId);
        }
        return new HttpEntity<>(body, headers);
    }

    @TestConfiguration
    static class QuoteGatewayTestConfiguration {
        @Bean
        @Primary
        QuoteGateway testQuoteGateway() {
            return quoteId -> {
                if ("quote_expired".equals(quoteId)) {
                    throw new QuoteExpiredException();
                }
                if ("quote_2".equals(quoteId)) {
                    return quote("quote_2", "applicant_001", "120000.00", 24);
                }
                return quote("quote_1", "applicant_001", "100000.00", 12);
            };
        }

        private static AcceptedQuote quote(String quoteId, String applicantId, String amount, int term) {
            return new AcceptedQuote(
                    quoteId,
                    applicantId,
                    new BigDecimal(amount),
                    term,
                    "debt_consolidation",
                    new BigDecimal("8560.75"),
                    new BigDecimal("0.0520"),
                    new BigDecimal("2729.00"),
                    new BigDecimal("102729.00"),
                    Instant.now().plusSeconds(3600));
        }
    }
}
