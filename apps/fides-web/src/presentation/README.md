# presentation 层

React 屏、组件、hooks、UI state、路由、样式与设计令牌。唯一可 import `react` 的业务层（与 `src/app/` 一道）。只经 `adapters` 的 controller/presenter 触发业务、消费 view model。

## 允许依赖

- `adapters`、UI 组件、`react`、路由、样式。

## 禁止依赖

- 直接 use case（`application`）、repository/gateway（`infrastructure`）实现类
- 复制领域规则、拼接后端 payload

## 门禁规则

- `presentation-cannot-depend-on-use-cases-or-repos`

> 子域在层下创建，如 `src/presentation/origination/`；`src/app/` 为最外路由入口，同受本层规则约束。
