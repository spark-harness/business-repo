package com.spark.quote.application;

import java.math.BigDecimal;

public record CreateQuoteCommand(String productCode, BigDecimal amount, int term, String purpose) {}
