# adapters 层

边界转换：controller（接收 UI 动作并调用应用用例）、presenter（把应用结果转换成 view model）、mapper。不实现真实 I/O，不含 React。

## 允许依赖

- `application`、`domain`。

## 禁止依赖

- `infrastructure` / `presentation` / `src/api`
- `react`、真实 HTTP/localStorage/第三方 SDK、JSX

## 门禁规则

- `adapters-cannot-depend-on-outer`
- `no-react-in-core`

> 子域在层下创建，如 `src/adapters/origination/`。
