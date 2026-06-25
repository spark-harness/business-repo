# application 层

用例编排：use case、command、query、result，以及 repository/gateway/clock/id 等**端口接口**定义。编排 domain 完成业务判断，但不知道端口由谁实现。

## 允许依赖

- `domain`、应用层本地类型、端口接口。

## 禁止依赖

- `adapters` / `infrastructure` / `presentation` / `src/api`
- `react`、`fetch`/axios/生成 API client、DOM/router/toast

## 门禁规则

- `application-cannot-depend-on-outer`
- `no-react-in-core`

> 子域在层下创建，如 `src/application/origination/`。
