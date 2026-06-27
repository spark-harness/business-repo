package com.spark.origination.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.common.spring.security.RequestPrincipalContext;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class HttpQuoteGateway implements QuoteGateway {
    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public HttpQuoteGateway(HttpClient client, String baseUrl, Duration timeout, ObjectMapper objectMapper) {
        this.client = client;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        this.objectMapper = objectMapper;
    }

    @Override
    public AcceptedQuote get(String quoteId) {
        String applicantId = RequestPrincipalContext.current()
                .orElseThrow(QuoteUnavailableException::new)
                .applicantId();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/internal/v1/pricing/quotes/" + quoteId))
                .timeout(timeout)
                .header("x-applicant-id", applicantId)
                .GET();
        forwardTraceHeader(request, "traceparent");
        forwardTraceHeader(request, "tracestate");
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new QuoteUnavailableException();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new QuoteUnavailableException();
        }
        return switch (response.statusCode()) {
            case 200 -> parseQuote(response.body(), applicantId);
            case 403 -> throw new ForbiddenException();
            case 404 -> throw new QuoteNotFoundException();
            case 410 -> throw new QuoteExpiredException();
            default -> throw new QuoteUnavailableException();
        };
    }

    private AcceptedQuote parseQuote(String body, String applicantId) {
        try {
            JsonNode values = objectMapper.readTree(body);
            return new AcceptedQuote(
                    required(values, "quoteId"),
                    applicantId,
                    new BigDecimal(required(values, "amount")),
                    Integer.parseInt(required(values, "term")),
                    required(values, "purpose"),
                    new BigDecimal(required(values, "monthly")),
                    new BigDecimal(required(values, "apr")),
                    new BigDecimal(required(values, "totalInterest")),
                    new BigDecimal(required(values, "totalPayable")),
                    Instant.parse(required(values, "validUntil")));
        } catch (JsonProcessingException | RuntimeException error) {
            throw new QuoteUnavailableException();
        }
    }

    private static String required(JsonNode values, String field) {
        JsonNode node = values.get(field);
        String value = node == null ? "" : node.asText();
        if (value == null || value.isBlank()) {
            throw new ValidationException("quote response missing " + field);
        }
        return value;
    }

    private static void forwardTraceHeader(HttpRequest.Builder request, String headerName) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return;
        }
        HttpServletRequest currentRequest = servletAttributes.getRequest();
        String headerValue = currentRequest.getHeader(headerName);
        if (headerValue != null && !headerValue.isBlank()) {
            request.header(headerName, headerValue);
        }
    }
}
