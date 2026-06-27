# fides

Spark 业务流程的前端独立应用（Next.js 16 App Router + TypeScript + Tailwind v4）。

遵循 Clean Architecture 五层（`domain` / `application` / `adapters` / `infrastructure` / `presentation`）+ 最外 `src/api`，依赖方向由 `dependency-cruiser` 静态门禁守护。

## 命令

```bash
pnpm dev          # 本地开发
pnpm build        # 生产构建
pnpm lint:deps    # Clean Architecture 依赖门禁（违规即非零退出）
pnpm dep:graph    # 生成依赖图（需 graphviz dot）
```

## 手机验证真实 BFF 接入

默认手机验证使用 mock adapter。连接本地 `fides-bff` 时使用运行时变量：

```bash
FIDES_OTP_ADAPTER=real
FIDES_BFF_BASE_URL=http://localhost:8000/api/v1
pnpm dev
```

`fides` 镜像构建不需要 OTP adapter、BFF base URL 或浏览器 tracing endpoint/header。环境差异配置按以下顺序在服务端运行时合并：

1. 默认值。
2. Consul JSON，默认 key 为 `spark/lendora/{FIDES_RUNTIME_ENV}/fides-web/runtime-config`。
3. 运行时环境变量或 Next 已加载的 `.env*`。

支持的运行时变量：

| Variable | Purpose |
|---|---|
| `FIDES_RUNTIME_ENV` | 当前环境名，默认 `local` |
| `FIDES_RUNTIME_CONFIG_CONSUL_URL` | Consul HTTP base URL |
| `FIDES_RUNTIME_CONFIG_CONSUL_KEY` | Consul KV key |
| `FIDES_OTP_ADAPTER` | `real`、`mock` 或 `disabled` |
| `FIDES_BFF_BASE_URL` | 浏览器访问 `fides-bff` 的 base URL |
| `FIDES_BROWSER_TRACING_ENDPOINT` | Browser OTLP traces endpoint，留空关闭导出 |
| `FIDES_BROWSER_TRACING_HEADERS` | Browser OTLP traces public headers，格式为 `k=v,k2=v2` |

旧 `NEXT_PUBLIC_FIDES_*` 和 `NEXT_PUBLIC_OTEL_*` 变量会被配置校验拒绝。

真实 adapter 调用：

| Method | Path |
|---|---|
| POST | `/auth/otp:send` |
| POST | `/auth/otp:verify` |
| POST | `/auth/token:refresh` |

每个写请求都会发送 `Idempotency-Key` header。前端只消费 BFF 的 REST JSON 和统一错误信封，不直接依赖 generated proto。

浏览器侧 tracing 使用 OpenTelemetry 官方 Web SDK 和 OTLP HTTP exporter。即使未开启导出，发往 BFF 的请求也会携带 W3C `traceparent` 与 `X-Trace-Id`，用于串起 FE → BFF → BE 链路。直连 Sentry Direct OTLP 时使用项目 DSN 中的 host、project id、public key：

```bash
FIDES_BROWSER_TRACING_ENDPOINT=<otlp-traces-endpoint>
FIDES_BROWSER_TRACING_HEADERS='<otlp-traces-public-headers>'
```

以上仍是标准 OTLP exporter 配置，没有引入 Sentry SDK。

## 架构约束

规则见 `.dependency-cruiser.cjs` 与各层 `src/<层>/README.md`，语义对齐
`clean-architecture-guide.md` §1-§4。Argo repo gate 以 `spark/fides-ci`
运行 `pnpm lint:deps` 并回写 GitHub required status，违规阻断合并。
