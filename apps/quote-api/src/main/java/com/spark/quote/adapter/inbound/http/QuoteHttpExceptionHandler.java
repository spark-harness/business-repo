package com.spark.quote.adapter.inbound.http;

import com.spark.quote.application.QuoteExpiredException;
import com.spark.quote.application.QuoteForbiddenException;
import com.spark.quote.application.QuoteNotFoundException;
import com.spark.quote.application.UnauthorizedException;
import com.spark.quote.domain.AmountOutOfRangeException;
import com.spark.quote.domain.ValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class QuoteHttpExceptionHandler {
    @ExceptionHandler(AmountOutOfRangeException.class)
    ResponseEntity<Map<String, String>> amountOutOfRange() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "amount_out_of_range");
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error");
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @ExceptionHandler(QuoteForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "forbidden");
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    ResponseEntity<Map<String, String>> quoteNotFound() {
        return error(HttpStatus.NOT_FOUND, "quote_not_found");
    }

    @ExceptionHandler(QuoteExpiredException.class)
    ResponseEntity<Map<String, String>> quoteExpired() {
        return error(HttpStatus.GONE, "quote_expired");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
