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

默认 local/dev 配置接入真实 PostgreSQL、Redis 和 Consul：

- PostgreSQL: `postgresql://forest:forest_dev_password@localhost:5432/app`
- Redis: `redis://:forest_dev_password@localhost:6379`
- Consul UI: `http://localhost:8500`

普通 `mvn test` 会在测试注解中显式覆盖为 `in-memory` runtime store，不依赖本机中间件。直接启动应用时默认使用 `redis-jdbc`、`test` OTP provider 和 `hmac` token mode。

启动本地依赖：

```bash
docker compose -f docker-compose.local.yml up -d
```

启动服务：

```bash
APPLICANT_TOKEN_SECRET=local-dev-token-secret \
APPLICANT_MIGRATIONS_ENABLED=true \
mvn spring-boot:run
```

服务启动时会先执行 `classpath:db/migration` 下的 Flyway 版本迁移；迁移成功后应用继续启动，迁移失败则启动失败。

本地默认不导出 trace。如需把 OpenTelemetry trace 通过 OTLP 发到远端后端，额外提供标准 OTLP endpoint、header 和采样率。后端可以是 Sentry，也可以是 Collector、Tempo 或其他兼容 OTLP 的系统。

```bash
OTEL_TRACES_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://o123456.ingest.sentry.io/api/1/otel/v1/traces \
OTEL_EXPORTER_OTLP_TRACES_HEADERS='x-sentry-auth=sentry sentry_key=public,sentry_version=7' \
OTEL_TRACES_SAMPLER_ARG=1.0 \
APPLICANT_TOKEN_SECRET=local-dev-token-secret \
APPLICANT_MIGRATIONS_ENABLED=true \
mvn spring-boot:run
```

重置本地数据：

```bash
ALLOW_LOCAL_RUNTIME_RESET=true ./scripts/reset-local-runtime.sh
```

执行一次真实本地 smoke：

```bash
./scripts/local-smoke.sh
```

smoke 会通过 gRPC 调用 `SendOtp` / `VerifyOtp`，再验证 PostgreSQL 中存在对应 applicant 记录，并验证 Redis 中存在 `applicant-api:*` runtime key。

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
      migrations-enabled: ${APPLICANT_MIGRATIONS_ENABLED:true}
      consul:
        enabled: true
        url: ${APPLICANT_CONSUL_URL}
        service-address: ${APPLICANT_SERVICE_ADDRESS}

otel:
  service:
    name: applicant-api
  propagators: tracecontext,baggage
  traces:
    exporter: otlp
    sampler: parentbased_traceidratio
    sampler.arg: ${OTEL_TRACES_SAMPLER_ARG}
  exporter:
    otlp:
      traces:
        endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}
        headers: ${OTEL_EXPORTER_OTLP_TRACES_HEADERS}
```

生产 profile 会在启动时拒绝 `test` OTP provider、非 `redis-jdbc` runtime store、非 `hmac` token mode、空 JDBC URL、空 Redis host、空 Consul URL、空 Consul service address、空 token secret、空 OTLP traces endpoint 或空 OTLP traces headers。

数据库 schema 由 Flyway 版本迁移管理。默认启用 `migrations-enabled`，如需临时关闭可设置 `APPLICANT_MIGRATIONS_ENABLED=false`。

## Readiness

`/ready` 汇总本地真实运行时依赖：

- `postgresql`: 执行 `select 1`
- `redis`: 执行 `PING`
- `consul`: 确认本进程已向 Consul agent 注册服务

Consul 注册包含服务名 `applicant-api`、HTTP port、gRPC port metadata 和 `/ready` HTTP health check。

## 可观测性

gRPC 入站适配器记录低基数指标：

- `applicant.auth.requests`
- `applicant.auth.duration`

标签仅包含 `operation`、`result` 和稳定 `error_code`，不包含手机号、OTP、token、applicantId 或 challengeId。失败路径会在当前 OpenTelemetry span 上标记 `error_code`，并输出包含 `service`、`operation`、`result`、`error_code`、`trace_id`、`span_id` 且不含敏感字段的结构化日志。

Trace 由 OpenTelemetry 官方 SDK / Spring Boot instrumentation 采集，并通过标准 OTLP exporter 导出。`service.name` 固定为服务矩阵中的 `applicant-api`。仓库不绑定供应商 SDK，不保存真实 endpoint 或认证 header；生产和需要外发的本地验证都必须通过环境变量提供。

数据库访问通过 OpenTelemetry JDBC instrumentation 采集，`JdbcTemplate` 查询和更新会生成 DB span，并随同服务 trace 通过同一个 OTLP traces exporter 发出。
