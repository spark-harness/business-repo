# Business Repo

这个仓库是业务代码仓骨架。

它负责保存：

- 服务代码。
- 单元测试、集成测试和验收辅助脚本。
- 服务内部实现说明。
- 由 protobuf 契约生成并被业务代码消费的代码。

## 目录

```text
business-repo/
├── apps/
├── packages/
└── tooling/
```

## Apps

可部署应用放在 `apps/`。每个应用目录必须能独立说明构建、测试和运行入口。

当前应用：

- `apps/fides-web`：Lendora 前端应用。
- `apps/fides-bff`：前端 BFF。
- `apps/applicant-api`：申请人身份与 OTP 后端服务。

## Packages

跨应用复用库放在 `packages/`，并按语言分组。

当前公共包：

- `packages/go/bffkit`：Go BFF 横切能力。
- `packages/java/money`：统一金额、币种、舍入和最小货币单位转换。
- `packages/java/spring-starter`：Spring Boot 干净架构公共 starter，提供分层 stereotype 和事务执行抽象。

## Tooling

仓库工具放在 `tooling/`。工具的脚本、测试和 fixtures 应放在同一个工具目录内。

当前工具：

- `tooling/contract-dependency-scan`：契约依赖版本扫描。

## 边界

- 不在这里维护 Harness 流程、门禁和需求状态。
- 不在这里维护 protobuf 契约真相源。
- 服务语义入口和跨服务关系由 `../harness-repo` 维护。
