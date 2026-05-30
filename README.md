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
├── packages/
└── services/
```

## 公共包

跨服务复用的业务公共库放在 `packages/`。

当前公共包：

- `packages/money`：统一金额、币种、舍入和最小货币单位转换。

## 边界

- 不在这里维护 Harness 流程、门禁和需求状态。
- 不在这里维护 protobuf 契约真相源。
- 服务语义入口和跨服务关系由 `../harness-repo` 维护。
