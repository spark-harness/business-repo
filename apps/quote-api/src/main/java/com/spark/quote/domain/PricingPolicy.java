package com.spark.quote.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public class PricingPolicy {
    private static final String PRODUCT_CODE = "PIL";
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("5000.00");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("500000.00");
    private static final Set<Integer> TERMS = Set.of(3, 6, 9, 12, 24);
    private static final BigDecimal APR = new BigDecimal("0.0520");
    private static final BigDecimal INTEREST_FACTOR = new BigDecimal("0.02729");
    private static final BigDecimal BASE_TERM_MONTHS = new BigDecimal("12");

    public QuoteCalculation calculate(String productCode, BigDecimal amount, int termMonths) {
        validate(productCode, amount, termMonths);

        BigDecimal term = BigDecimal.valueOf(termMonths);
        BigDecimal termFactor = term.divide(BASE_TERM_MONTHS, 10, RoundingMode.HALF_UP);
        BigDecimal totalInterest = amount.multiply(INTEREST_FACTOR).multiply(termFactor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPayable = amount.add(totalInterest).setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthly = totalPayable.divide(term, 2, RoundingMode.HALF_UP);
        return new QuoteCalculation(monthly, APR, totalInterest, totalPayable);
    }

    private void validate(String productCode, BigDecimal amount, int termMonths) {
        if (!PRODUCT_CODE.equals(productCode)) {
            throw new ValidationException("unsupported product code");
        }
        if (amount == null
                || amount.compareTo(MIN_AMOUNT) < 0
                || amount.compareTo(MAX_AMOUNT) > 0
                || !TERMS.contains(termMonths)) {
            throw new AmountOutOfRangeException("amount or term is outside product range");
        }
    }

    public record QuoteCalculation(
            BigDecimal monthly, BigDecimal apr, BigDecimal totalInterest, BigDecimal totalPayable) {}
}
