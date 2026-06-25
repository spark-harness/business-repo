package com.spark.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void convertsCnyToMinorUnits() {
        Money money = Money.of("CNY", "100.01");

        assertEquals(new BigDecimal("100.01"), money.amount());
        assertEquals(BigInteger.valueOf(10001), money.minorUnits());
    }

    @Test
    void createsHkdMoneyWithShortcut() {
        Money money = Money.hkd("88.888", RoundingMode.HALF_UP);

        assertEquals("HKD", money.currencyCode());
        assertEquals(new BigDecimal("88.89"), money.amount());
        assertEquals(BigInteger.valueOf(8889), money.minorUnits());
    }

    @Test
    void keepsJpyMinorUnitsAtMajorScale() {
        Money money = Money.of("JPY", "100.4", RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("100"), Money.of("JPY", "100.4", RoundingMode.DOWN).amount());
        assertEquals(BigInteger.valueOf(100), money.minorUnits());
    }

    @Test
    void rejectsCrossCurrencyAddition() {
        Money cny = Money.of("CNY", "10.00");
        Money usd = Money.of("USD", "10.00");

        assertThrows(IllegalArgumentException.class, () -> cny.add(usd));
    }

    @Test
    void canCreateFromMinorUnits() {
        Money money = Money.fromMinorUnits("USD", 12345L);

        assertEquals(new BigDecimal("123.45"), money.amount());
    }
}
