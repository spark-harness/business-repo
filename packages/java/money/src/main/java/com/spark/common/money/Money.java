package com.spark.common.money;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public final class Money implements Comparable<Money>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.currency = requireCurrency(currency);
        this.amount = normalize(amount, this.currency, DEFAULT_ROUNDING);
    }

    public static Money of(String currencyCode, String amount) {
        return of(currencyCode, new BigDecimal(Objects.requireNonNull(amount, "amount")));
    }

    public static Money of(String currencyCode, BigDecimal amount) {
        return of(Currency.getInstance(currencyCode), amount);
    }

    public static Money hkd(String amount) {
        return of("HKD", amount);
    }

    public static Money hkd(BigDecimal amount) {
        return of("HKD", amount);
    }

    public static Money hkd(String amount, RoundingMode roundingMode) {
        return of("HKD", amount, roundingMode);
    }

    public static Money hkd(BigDecimal amount, RoundingMode roundingMode) {
        return of("HKD", amount, roundingMode);
    }

    public static Money of(String currencyCode, String amount, RoundingMode roundingMode) {
        return of(currencyCode, new BigDecimal(Objects.requireNonNull(amount, "amount")), roundingMode);
    }

    public static Money of(Currency currency, BigDecimal amount) {
        return of(currency, amount, DEFAULT_ROUNDING);
    }

    public static Money of(String currencyCode, BigDecimal amount, RoundingMode roundingMode) {
        return of(Currency.getInstance(currencyCode), amount, roundingMode);
    }

    public static Money of(Currency currency, BigDecimal amount, RoundingMode roundingMode) {
        Currency checkedCurrency = requireCurrency(currency);
        return new Money(normalize(amount, checkedCurrency, roundingMode), checkedCurrency);
    }

    public static Money fromMinorUnits(String currencyCode, long minorUnits) {
        return fromMinorUnits(Currency.getInstance(currencyCode), BigInteger.valueOf(minorUnits));
    }

    public static Money fromMinorUnits(String currencyCode, BigInteger minorUnits) {
        return fromMinorUnits(Currency.getInstance(currencyCode), minorUnits);
    }

    public static Money fromMinorUnits(Currency currency, BigInteger minorUnits) {
        Currency checkedCurrency = requireCurrency(currency);
        Objects.requireNonNull(minorUnits, "minorUnits");
        BigDecimal majorAmount = new BigDecimal(minorUnits).movePointLeft(scaleOf(checkedCurrency));
        return new Money(majorAmount, checkedCurrency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal multiplier) {
        return multiply(multiplier, DEFAULT_ROUNDING);
    }

    public Money multiply(BigDecimal multiplier, RoundingMode roundingMode) {
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(roundingMode, "roundingMode");
        return new Money(amount.multiply(multiplier).setScale(scaleOf(currency), roundingMode), currency);
    }

    public Money divide(BigDecimal divisor, RoundingMode roundingMode) {
        Objects.requireNonNull(divisor, "divisor");
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return new Money(amount.divide(divisor, scaleOf(currency), roundingMode), currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigInteger minorUnits() {
        return amount.movePointRight(scaleOf(currency)).toBigIntegerExact();
    }

    public Currency currency() {
        return currency;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money money)) {
            return false;
        }
        return amount.equals(money.amount) && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " != " + other.currency.getCurrencyCode());
        }
    }

    private static BigDecimal normalize(BigDecimal amount, Currency currency, RoundingMode roundingMode) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(roundingMode, "roundingMode");
        return amount.setScale(scaleOf(currency), roundingMode);
    }

    private static Currency requireCurrency(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        scaleOf(currency);
        return currency;
    }

    private static int scaleOf(Currency currency) {
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) {
            throw new IllegalArgumentException("Currency has no decimal fraction digits: " + currency.getCurrencyCode());
        }
        return scale;
    }
}
