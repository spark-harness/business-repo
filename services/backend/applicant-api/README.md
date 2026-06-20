# applicant-api

`applicant-api` 是 Lendora Applicant 身份服务，负责手机号 OTP、Applicant 身份、短期 access token 和 refresh token。

它不是 `fides-bff`，也不处理前端页面、贷款申请、KYC 或资源归属校验。

## 目录

```text
src/main/java/com/spark/applicant/
├── bootstrap/
├── domain/
├── application/
├── adapter/inbound/
└── infrastructure/
```

## 本地测试

```bash
mvn test
```

服务依赖同分支 `spark-spring-clean-architecture-starter` 与 `spark-idl-java`。本地未发布 snapshot 时，先在对应仓库运行 `mvn install`。

## 运行配置

默认配置使用 `in-memory` runtime store、`test` OTP provider 和 `simple` token mode，适合本地开发和单元测试，不依赖外部 Redis。

生产 profile 使用 `redis-jdbc` runtime store、`disabled` OTP provider 和 `hmac` token mode：

```yaml
spring:
  profiles:
    active: prod
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2s

spark:
  applicant:
    auth:
      jdbc-url: jdbc:postgresql://localhost:5432/applicant
      jdbc-username: applicant
      jdbc-password: ${APPLICANT_DB_PASSWORD}
      token-secret: ${APPLICANT_TOKEN_SECRET}
      initialize-schema: false
```

生产 profile 会在启动时拒绝 `test` OTP provider、非 `redis-jdbc` runtime store、非 `hmac` token mode、空 JDBC URL 或空 token secret。

`initialize-schema` 只用于本地或临时环境初始化最小 applicant 表；正式环境应由迁移工具管理 schema。

## 可观测性

gRPC 入站适配器记录低基数指标：

- `applicant.auth.requests`
- `applicant.auth.duration`

标签仅包含 `operation`、`result` 和稳定 `error_code`，不包含手机号、OTP、token、applicantId 或 challengeId。失败路径会在当前 OpenTelemetry span 上标记 `error_code`，并输出包含 `service`、`operation`、`result`、`error_code`、`trace_id`、`span_id` 且不含敏感字段的结构化日志。
