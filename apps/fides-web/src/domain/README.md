# domain 层

业务核心：实体、值对象、领域枚举、领域错误，以及不依赖 UI/接口的业务规则（如 HKID 校验位、金额/期限区间、申请完整性、状态流转、Quote）。`no-react`，可与后端同源。

## 允许依赖

- 语言标准库、稳定的业务值对象。

## 禁止依赖

- `application` / `adapters` / `infrastructure` / `presentation` / `src/api`
- `react`、浏览器能力、HTTP client、生成 API client

## 门禁规则

- `domain-cannot-depend-on-outer`
- `no-react-in-core`

> 目录按「层优先」组织：子域在层下创建，如 `src/domain/kyc/`。
