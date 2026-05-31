# Services

真实服务接入时，按服务或领域建立目录。

示例：

```text
services/
├── backend/
│   └── user-api/
├── order-api/
└── order-worker/
```

服务是否涉及 protobuf 契约，以 `../../harness-repo/.service-matrix/dependencies.yaml` 为准。

服务需要处理金额时，应依赖 `business-repo/packages/money` 对应发布物，不在服务目录内重复实现金额类型或元分换算逻辑。
