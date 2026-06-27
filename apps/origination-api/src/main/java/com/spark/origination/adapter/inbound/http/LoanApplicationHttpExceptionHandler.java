package com.spark.origination.adapter.inbound.http;

import com.spark.origination.application.AmountOutOfRangeException;
import com.spark.origination.application.ApplicationNotFoundException;
import com.spark.origination.application.ForbiddenException;
import com.spark.origination.application.IdempotencyKeyConflictException;
import com.spark.origination.application.IdempotencyKeyRequiredException;
import com.spark.origination.application.QuoteExpiredException;
import com.spark.origination.application.QuoteNotFoundException;
import com.spark.origination.application.QuoteUnavailableException;
import com.spark.origination.application.UnauthorizedException;
import com.spark.origination.domain.ValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LoanApplicationHttpExceptionHandler {
    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    ResponseEntity<Map<String, String>> idempotencyKeyRequired() {
        return error(HttpStatus.BAD_REQUEST, "idempotency_key_required");
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<Map<String, String>> idempotencyKeyConflict() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error");
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "forbidden");
    }

    @ExceptionHandler({ApplicationNotFoundException.class, QuoteNotFoundException.class})
    ResponseEntity<Map<String, String>> notFound() {
        return error(HttpStatus.NOT_FOUND, "not_found");
    }

    @ExceptionHandler(QuoteExpiredException.class)
    ResponseEntity<Map<String, String>> quoteExpired() {
        return error(HttpStatus.GONE, "quote_expired");
    }

    @ExceptionHandler(AmountOutOfRangeException.class)
    ResponseEntity<Map<String, String>> amountOutOfRange() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "amount_out_of_range");
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error");
    }

    @ExceptionHandler(QuoteUnavailableException.class)
    ResponseEntity<Map<String, String>> quoteUnavailable() {
        return error(HttpStatus.BAD_GATEWAY, "quote_unavailable");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
