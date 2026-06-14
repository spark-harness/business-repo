# fides

Spark 业务流程的前端独立应用（Next.js 16 App Router + TypeScript + Tailwind v4）。

遵循 Clean Architecture 五层（`domain` / `application` / `adapters` / `infrastructure` / `presentation`）+ 最外 `src/api`，依赖方向由 `dependency-cruiser` 静态门禁守护。

## 命令

```bash
pnpm dev          # 本地开发
pnpm build        # 生产构建
pnpm lint:deps    # Clean Architecture 依赖门禁（违规即非零退出）
pnpm dep:graph    # 生成依赖图（需 graphviz dot）
```

## 架构约束

规则见 `.dependency-cruiser.cjs` 与各层 `src/<层>/README.md`，语义对齐
`clean-architecture-guide.md` §1-§4。CI 在 `.github/workflows/fides-ci.yml`
运行 `pnpm lint:deps`，违规阻断合并。
