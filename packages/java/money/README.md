# spark-money

`spark-money` 是团队金额公共库，用于统一金额、币种、舍入和最小货币单位转换。

## Maven 坐标

```xml
<dependency>
  <groupId>com.spark.common</groupId>
  <artifactId>spark-money</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 使用示例

```java
Money payAmount = Money.of("CNY", "100.00");
Money fee = payAmount.multiply(new BigDecimal("0.03"), RoundingMode.HALF_UP);
BigDecimal amountForDb = fee.amount();
BigInteger amountForChannel = fee.minorUnits();
```

港币快捷入口：

```java
Money amount = Money.hkd("100.00");
Money rounded = Money.hkd("88.888", RoundingMode.HALF_UP);
```

## 约束

- 不提供 `double` 或 `float` 构造入口。
- 加、减和比较只允许相同币种。
- 内部存储和传输使用主单位金额加币种。
- 最小货币单位只在外部渠道适配层使用。
