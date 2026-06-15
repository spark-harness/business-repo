# api 层（最外）

Server Actions / 路由处理器：写操作/提交的服务端代理，避免 PII 在客户端长存。作为最外编排层（composition root），可向内组合各层装配端口实现。

## 依赖方向

- 可向内依赖 `domain` / `application` / `adapters` / `infrastructure` 进行装配与代理。
- **内层（`domain` / `application` / `adapters`）不得依赖本层**——它们对应规则的禁止 `to` 含顶层 `src/api/`。

## 门禁规则

- 作为 `domain-cannot-depend-on-outer` / `application-cannot-depend-on-outer` / `adapters-cannot-depend-on-outer` 的禁止目标（顶层 `src/api/`）。

> `presentation`/`app` 可经 Server Actions 提交写操作；本层不放业务规则。
