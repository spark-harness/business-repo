package com.spark.quote.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.bootstrap.QuoteApiApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = QuoteApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spark.quote.jdbc-url=jdbc:h2:mem:quote-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class QuoteHttpAdapterTest {
    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void ready_withDatabaseAvailable_returnsReady() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/ready"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "READY");
    }

    @Test
    void createQuote_withApplicantHeader_returnsPricing() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-applicant-id", "applicant_001");
        headers.add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        Map<String, Object> request = Map.of(
                "productCode", "PIL",
                "amount", "100000.00",
                "term", 12,
                "purpose", "debt_consolidation");

        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/v1/pricing/quotes"),
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("monthly", "8560.75")
                .containsEntry("apr", "0.0520")
                .containsEntry("totalInterest", "2729.00")
                .containsEntry("totalPayable", "102729.00");
        assertThat(response.getBody()).doesNotContainKeys("productCode", "amount", "term", "purpose");
        assertThat((String) response.getBody().get("quoteId")).startsWith("quote_");
    }

    @Test
    void getInternalQuote_withApplicantHeader_returnsPersistedQuote() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-applicant-id", "applicant_001");
        Map<String, Object> request = Map.of(
                "productCode", "PIL",
                "amount", "100000.00",
                "term", 12,
                "purpose", "debt_consolidation");
        ResponseEntity<Map> created = restTemplate.exchange(
                url("/api/v1/pricing/quotes"),
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);
        String quoteId = (String) created.getBody().get("quoteId");

        ResponseEntity<Map> response = restTemplate.exchange(
                url("/internal/v1/pricing/quotes/" + quoteId),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("quoteId", quoteId)
                .containsEntry("productCode", "PIL")
                .containsEntry("amount", "100000.00")
                .containsEntry("term", 12)
                .containsEntry("purpose", "debt_consolidation");
    }

    @Test
    void createQuote_withoutApplicantHeader_returnsUnauthorized() {
        Map<String, Object> request = Map.of(
                "productCode", "PIL",
                "amount", "100000.00",
                "term", 12,
                "purpose", "debt_consolidation");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                url("/api/v1/pricing/quotes"), request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "unauthorized");
    }

    @Test
    void createQuote_withOutOfRangeAmount_returnsAmountOutOfRange() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-applicant-id", "applicant_001");
        Map<String, Object> request = Map.of(
                "productCode", "PIL",
                "amount", "4999.99",
                "term", 12,
                "purpose", "debt_consolidation");

        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/v1/pricing/quotes"),
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("error", "amount_out_of_range");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
