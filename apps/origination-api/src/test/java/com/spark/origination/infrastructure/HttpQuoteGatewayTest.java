package com.spark.origination.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.support.TestPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class HttpQuoteGatewayTest {
    private HttpServer server;
    private HttpQuoteGateway gateway;
    private String applicantHeader;
    private String traceparentHeader;
    private String tracestateHeader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway = new HttpQuoteGateway(
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2),
                new ObjectMapper());
        TestPrincipal.set("applicant_001");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void get_withSuccessfulResponse_parsesQuoteAndForwardsIdentityAndTracing() {
        MockHttpServletRequest currentRequest = new MockHttpServletRequest();
        currentRequest.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        currentRequest.addHeader("tracestate", "vendor=value");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(currentRequest));
        server.createContext("/internal/v1/pricing/quotes/quote_1", exchange -> {
            applicantHeader = exchange.getRequestHeaders().getFirst("x-applicant-id");
            traceparentHeader = exchange.getRequestHeaders().getFirst("traceparent");
            tracestateHeader = exchange.getRequestHeaders().getFirst("tracestate");
            respond(exchange, 200, """
                    {
                      "quoteId": "quote_1",
                      "amount": "100000.00",
                      "term": 12,
                      "purpose": "debt_consolidation",
                      "monthly": "8560.75",
                      "apr": "0.0520",
                      "totalInterest": "2729.00",
                      "totalPayable": "102729.00",
                      "validUntil": "2026-06-28T23:59:00Z"
                    }
                    """);
        });
        server.start();

        AcceptedQuote quote = gateway.get("quote_1");

        assertThat(applicantHeader).isEqualTo("applicant_001");
        assertThat(traceparentHeader).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(tracestateHeader).isEqualTo("vendor=value");
        assertThat(quote.quoteId()).isEqualTo("quote_1");
        assertThat(quote.applicantId()).isEqualTo("applicant_001");
        assertThat(quote.amount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void get_withDownstreamStatus_mapsStableExceptions() {
        server.createContext("/internal/v1/pricing/quotes/forbidden", exchange -> exchange.sendResponseHeaders(403, -1));
        server.createContext("/internal/v1/pricing/quotes/missing", exchange -> exchange.sendResponseHeaders(404, -1));
        server.createContext("/internal/v1/pricing/quotes/expired", exchange -> exchange.sendResponseHeaders(410, -1));
        server.createContext("/internal/v1/pricing/quotes/error", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();

        assertThatThrownBy(() -> gateway.get("forbidden")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> gateway.get("missing")).isInstanceOf(QuoteNotFoundException.class);
        assertThatThrownBy(() -> gateway.get("expired")).isInstanceOf(QuoteExpiredException.class);
        assertThatThrownBy(() -> gateway.get("error")).isInstanceOf(QuoteUnavailableException.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
