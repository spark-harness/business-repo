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

默认手机验证使用 mock adapter。连接本地 `fides-bff` 时设置：

```bash
NEXT_PUBLIC_FIDES_OTP_ADAPTER=real
NEXT_PUBLIC_FIDES_BFF_BASE_URL=http://localhost:8000/api/v1
pnpm dev
```

真实 adapter 调用：

| Method | Path |
|---|---|
| POST | `/auth/otp:send` |
| POST | `/auth/otp:verify` |
| POST | `/auth/token:refresh` |

每个写请求都会发送 `Idempotency-Key` header。前端只消费 BFF 的 REST JSON 和统一错误信封，不直接依赖 generated proto。

浏览器侧 tracing 使用 OpenTelemetry 官方 Web SDK 和 OTLP HTTP exporter。即使未开启导出，发往 BFF 的请求也会携带 W3C `traceparent` 与 `X-Trace-Id`，用于串起 FE → BFF → BE 链路。直连 Sentry Direct OTLP 时使用项目 DSN 中的 host、project id、public key：

```bash
NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=<otlp-traces-endpoint>
NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_HEADERS='<otlp-traces-headers>'
```

以上仍是标准 OTLP exporter 配置，没有引入 Sentry SDK。

## 架构约束

规则见 `.dependency-cruiser.cjs` 与各层 `src/<层>/README.md`，语义对齐
`clean-architecture-guide.md` §1-§4。Argo repo gate 以 `spark/fides-ci`
运行 `pnpm lint:deps` 并回写 GitHub required status，违规阻断合并。
