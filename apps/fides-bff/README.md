# fides-bff

Lendora 前端 `fides` 的 BFF（Backend for Frontend）。对前端暴露 REST `/api/v1`，对内调用领域 gRPC 服务。

需求：`LEN-21`（父 Story `LEN-3` / Epic `LEN-1`）、`LEN-43`。本服务是申请漏斗各能力的统一后端入口与约定（错误信封 / 幂等 / 可观测 / gRPC→REST 映射），不实现具体业务能力。

## 范围与进度

本服务按 `harness-repo/requirements/LEN-21/tasks.json` 切片实现：

- **T1（当前）**：可运行 Kratos 骨架 + `GET /api/v1/health` + 本地一键运行 + Go CI。不含下游 gRPC、不依赖契约。
- T2：统一错误信封 + 字段校验 + gRPC status→REST 映射。
- T3：`Idempotency-Key` 幂等中间件 + `IdempotencyStore` 端口与实现。
- T4：可观测基线（结构化日志 + OTel tracing + traceId 透传）。

## 技术栈

- 语言：Go
- 框架：[Kratos v2](https://go-kratos.dev/)
- 依赖注入：[google/wire](https://github.com/google/wire)（`make generate` 重新生成 `wire_gen.go`）

## 目录结构（遵循 Kratos 布局 + 团队干净架构边界）

```text
cmd/fides-bff/      bootstrap：main + wire 装配
configs/            配置 yaml
internal/
  conf/             配置结构（plain Go，非 protobuf）
  server/           transport：HTTP(/api/v1) + 路由/中间件注册
  service/          adapter/inbound：handler、req/resp 映射、错误信封映射
  biz/              application + domain：用例与出站端口接口
  data/             infrastructure：出站实现（gRPC 客户端、幂等存储）—— T1 暂无
```

依赖方向：`service → biz`，`data → biz`（端口在 `biz` 定义、`data` 实现）；`server`/`cmd` 负责装配。

## 本地运行

需要 Go 1.26+。

```bash
make run                 # 启动服务，监听 0.0.0.0:8000（见 configs/config.yaml）
curl localhost:8000/api/v1/health
# {"status":"ok","version":"dev"}
```

手机验证本地接入依赖 Consul 发现 `applicant-api`。本地默认配置使用：

```text
Consul UI: http://localhost:8500
Consul API: 127.0.0.1:8500
Service name: applicant-api
```

`fides-bff` 启动时也会注册到 Consul，默认服务名为 `fides-bff`，发现地址为 `127.0.0.1:8000`。K8s 环境应通过 `registry.consul.service_name` 配置环境化服务名，例如 `dev-1-fides-bff`。本地如果修改 HTTP 监听端口，需要同步修改 `registry.consul.discovery_addr`，不要把 `0.0.0.0` 作为发现地址。

`fides-bff` 不保存 OTP 状态，也不硬编码 applicant 地址；如果 Consul 没有健康的 `applicant-api` 实例，auth API 返回统一错误信封，错误码为 `applicant_unavailable`。

本地联调也可以绕过 Consul，直接指定 gRPC target：

```bash
REGISTRY_CONSUL_ENABLED=false \
APPLICANT_GRPC_TARGET=127.0.0.1:19092 \
QUOTE_GRPC_TARGET=127.0.0.1:19090 \
ORIGINATION_GRPC_TARGET=127.0.0.1:19091 \
make run
```

OpenTelemetry 通过官方 SDK 和 OTLP exporter 初始化。默认 `observability.otel.traces_exporter=none`、`observability.otel.logs_exporter=none`；启用时使用标准 `OTEL_*` 环境变量设置 traces/logs exporter、endpoint、protocol 和 headers，可指向 OpenTelemetry Collector，也可直连兼容 OTLP 的后端。应用代码不依赖供应商 SDK、供应商 exporter 或供应商专用 span processor。

本地验证 OTLP HTTP 后端时，按后端提供的 traces/logs endpoint 和认证 header 配置：

```yaml
observability:
  otel:
    sdk_disabled: false
	    traces_exporter: otlp
	    traces_endpoint: "<otlp-traces-endpoint>"
	    traces_protocol: http/protobuf
	    traces_headers: "authorization=<otlp-auth-header>"
	    logs_exporter: otlp
	    logs_endpoint: "<otlp-logs-endpoint>"
	    logs_protocol: http/protobuf
	    logs_headers: "authorization=<otlp-auth-header>"
	    resource_attributes: "deployment.environment=local"
	    service_name: fides-bff
	    service_version: dev
```

这只是 OTLP HTTP exporter 的 header 配置；切换到 Collector 或其他 OTLP 后端时只需要替换 endpoint / headers。

## 常用命令

```bash
make test       # 单元测试
make lint       # golangci-lint
make build      # 编译到 bin/fides-bff（版本经 -ldflags 注入）
make generate   # 修改 DI 后重新生成 wire
make init       # 安装 wire / golangci-lint 工具
```

## 配置

启动期配置按以下优先级合并，后者覆盖前者：

1. `configs/config.yaml`：最低优先级默认值，并用 `${ENV:default}` 表达环境差异。
2. `.env`：只在本地启动前加载进进程环境；缺失不报错，且不覆盖已经存在的 shell、CI 或 K8s 环境变量。
3. Kratos env source：读取进程环境，用于解析 `configs/config.yaml` 中引用的占位符。

这不是运行时热更新；配置变更需要重启进程后生效。

`configs/config.yaml`：

```yaml
server:
  http:
    network: "${SERVER_HTTP_NETWORK:tcp}"
    addr: "${SERVER_HTTP_ADDR:0.0.0.0:8000}"
applicant:
  consul:
    address: "${APPLICANT_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${APPLICANT_CONSUL_SCHEME:http}"
    service_name: "${APPLICANT_CONSUL_SERVICE_NAME:applicant-api}"
  grpc:
    timeout: "${APPLICANT_GRPC_TIMEOUT:3s}"
    plaintext: "${APPLICANT_GRPC_PLAINTEXT:true}"
    target: "${APPLICANT_GRPC_TARGET:}"
registry:
  consul:
    enabled: "${REGISTRY_CONSUL_ENABLED:true}"
    address: "${REGISTRY_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${REGISTRY_CONSUL_SCHEME:http}"
    service_name: "${REGISTRY_CONSUL_SERVICE_NAME:fides-bff}"
    discovery_addr: "${REGISTRY_CONSUL_DISCOVERY_ADDR:127.0.0.1:8000}"
    heartbeat: "${REGISTRY_CONSUL_HEARTBEAT:true}"
    health_check: "${REGISTRY_CONSUL_HEALTH_CHECK:true}"
    health_check_interval_sec: "${REGISTRY_CONSUL_HEALTH_CHECK_INTERVAL_SEC:10}"
    deregister_after_sec: "${REGISTRY_CONSUL_DEREGISTER_AFTER_SEC:60}"
    metadata:
      module: "${REGISTRY_CONSUL_METADATA_MODULE:frontend}"
observability:
  otel:
    sdk_disabled: "${OTEL_SDK_DISABLED:false}"
    traces_exporter: "${OTEL_TRACES_EXPORTER:none}"
    traces_endpoint: "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:}"
    traces_protocol: "${OTEL_EXPORTER_OTLP_TRACES_PROTOCOL:http/protobuf}"
    traces_headers: "${OTEL_EXPORTER_OTLP_TRACES_HEADERS:}"
    resource_attributes: "${OTEL_RESOURCE_ATTRIBUTES:deployment.environment=local}"
    service_name: "${OTEL_SERVICE_NAME:fides-bff}"
    service_version: "${OTEL_SERVICE_VERSION:dev}"
```

本地可复制 `.env.example` 为 `.env` 后调整私有值。`.env` 已被 git ignore，不要提交真实 token、密码、Authorization header 或其他 secret。

`CONFIG_CONSUL_*` 不再参与启动配置加载。需要随环境变化的运行配置应通过 `configs/config.yaml` 中的 `${ENV:default}` 占位符表达，例如：

```bash
SERVER_HTTP_ADDR=127.0.0.1:8000
APPLICANT_CONSUL_ADDRESS=127.0.0.1:8500
REGISTRY_CONSUL_SERVICE_NAME=dev-1-fides-bff
REGISTRY_CONSUL_DISCOVERY_ADDR=127.0.0.1:8000
OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local
OTEL_SERVICE_NAME=fides-bff
```

这里删除的只是启动配置的 Consul KV source，不是服务注册/发现配置。`applicant.consul`、`quote.consul`、`origination.consul` 和 `registry.consul` 仍然通过环境占位符生效。

## API

| Method | Path | 说明 | 响应 |
|---|---|---|---|
| GET | `/api/v1/health` | 健康检查（liveness） | `{ "status": "ok", "version": "<build>" }` |
| POST | `/api/v1/auth/otp:send` | 请求手机验证码 | `{ "challengeId": "...", "expiresInSec": 300, "resendAfterSec": 60 }` |
| POST | `/api/v1/auth/otp:verify` | 验证 OTP 并返回会话结果 | `{ "accessToken": "...", "refreshToken": "...", "applicantId": "...", "expiresInSec": 3600 }` |
| POST | `/api/v1/auth/token:refresh` | 刷新 access token | `{ "accessToken": "...", "expiresInSec": 3600 }` |
