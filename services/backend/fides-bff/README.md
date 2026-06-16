# fides-bff

Lendora 前端 `fides` 的 BFF（Backend for Frontend）。对前端暴露 REST `/api/v1`，对内调用领域 gRPC 服务。

需求：`LEN-21`（父 Story `LEN-3` / Epic `LEN-1`）。本服务是申请漏斗各能力的统一后端入口与约定（错误信封 / 幂等 / 可观测 / gRPC→REST 映射），不实现具体业务能力。

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

需要 Go 1.24+。

```bash
make run                 # 启动服务，监听 0.0.0.0:8000（见 configs/config.yaml）
curl localhost:8000/api/v1/health
# {"status":"ok","version":"dev"}
```

## 常用命令

```bash
make test       # 单元测试
make lint       # golangci-lint
make build      # 编译到 bin/fides-bff（版本经 -ldflags 注入）
make generate   # 修改 DI 后重新生成 wire
make init       # 安装 wire / golangci-lint 工具
```

## 配置

`configs/config.yaml`：

```yaml
server:
  http:
    network: tcp
    addr: 0.0.0.0:8000
```

## API

| Method | Path | 说明 | 响应 |
|---|---|---|---|
| GET | `/api/v1/health` | 健康检查（liveness） | `{ "status": "ok", "version": "<build>" }` |
