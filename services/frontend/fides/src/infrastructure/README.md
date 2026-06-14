# infrastructure 层

端口实现：HTTP client、repository、gateway、storage、feature flag、analytics，以及生成 API client → domain/application 类型的转换。必须实现 `application`/`domain` 定义的端口。

## 允许依赖

- `application`/`domain` 的端口接口、技术 SDK、生成 API client。

## 禁止依赖

- `presentation`（含 `src/app/`）：React 组件、页面状态、toast/modal
- `react`

## 门禁规则

- `infrastructure-cannot-depend-on-presentation`
- `no-react-in-core`

> 子域/技术分组在层下创建，如 `src/infrastructure/http/`、`src/infrastructure/kyc/`。
