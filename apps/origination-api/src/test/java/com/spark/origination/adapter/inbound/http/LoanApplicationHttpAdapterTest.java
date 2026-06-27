package com.spark.origination.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.bootstrap.OriginationApiApplication;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OriginationApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApplicationHttpAdapterTest {
    private static HttpServer quoteServer;

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeAll
    static void startQuoteServer() throws IOException {
        quoteServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        quoteServer.createContext("/internal/v1/pricing/quotes/quote_1", exchange -> {
            String applicantId = exchange.getRequestHeaders().getFirst("x-applicant-id");
            if (!"applicant_001".equals(applicantId)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, quoteBody("quote_1", "100000.00", 12));
        });
        quoteServer.createContext("/internal/v1/pricing/quotes/quote_2", exchange ->
                respond(exchange, 200, quoteBody("quote_2", "120000.00", 24)));
        quoteServer.createContext("/internal/v1/pricing/quotes/quote_expired", exchange ->
                exchange.sendResponseHeaders(410, -1));
        quoteServer.start();
    }

    @AfterAll
    static void stopQuoteServer() {
        if (quoteServer != null) {
            quoteServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spark.origination.jdbc-url",
                () -> "jdbc:h2:mem:origination-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spark.origination.quote-api-base-url", () -> "http://127.0.0.1:" + quoteServer.getAddress().getPort());
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

    private static String quoteBody(String quoteId, String amount, int term) {
        return """
                {
                  "quoteId": "%s",
                  "productCode": "PIL",
                  "amount": "%s",
                  "term": %d,
                  "purpose": "debt_consolidation",
                  "monthly": "8560.75",
                  "apr": "0.0520",
                  "totalInterest": "2729.00",
                  "totalPayable": "102729.00",
                  "validUntil": "2026-06-28T23:59:00Z"
                }
                """.formatted(quoteId, amount, term);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
