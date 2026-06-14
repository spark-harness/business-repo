// Clean Architecture 静态依赖门禁。
//
// 规则语义见仓库 clean-architecture-guide.md §1-§4 与
// harness context/team/frontend-clean-architecture.md。
//
// 「子域优先」布局：层目录可在 src 下任意深度，既覆盖裸层根
//   src/domain/...，也覆盖未来子域嵌套 src/kyc/domain/...。
// anyDepth() 把层名编译成对应正则，刻意避免嵌套量词（如
//   ([^/]+/)* 或 (.*/)?），以通过 dependency-cruiser 的 safe-regex
//   （ReDoS）校验——这些写法星高为 2 会被拒；这里星高保持为 1。
//
// `src/app/` 视为 presentation 最外层（允许 React，受 presentation 规则约束）。
// `src/api/` 视为最外编排层（composition root）：内三层不得依赖它。

// 层名集合 -> 「src 下任意深度命中该层目录」的安全正则。
// 形如 ^src/(<层>/|.+/<层>/)：第一支匹配裸层根，第二支匹配子域前缀。
function anyDepth(layers) {
  const group = layers.join("|");
  return `^src/((${group})/|.+/(${group})/)`;
}

// 同 anyDepth，但额外把最外 src/api（top-level composition root）纳入禁止目标。
// api 只在顶层 src/api/ 命中，不波及 SSOT 模板里 infrastructure/api/ 这类层内子目录。
function anyDepthOrApi(layers) {
  const group = layers.join("|");
  return `^src/((${group})/|.+/(${group})/|api/)`;
}

// presentation 来源/目标：任意深度的 presentation 目录，或最外的 app。
const PRESENTATION = "^src/(presentation/|.+/presentation/|app/)";

// 解析到 npm 包 react / react-dom 的依赖（兼容 pnpm 嵌套 node_modules 与符号链接）。
const REACT = "[/\\\\]node_modules[/\\\\]react(-dom)?([/\\\\]|$)";

/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "domain-cannot-depend-on-outer",
      comment:
        "domain 是业务核心，依赖只向内：不得依赖 application/adapters/infrastructure/presentation/api。",
      severity: "error",
      from: { path: anyDepth(["domain"]) },
      to: { path: anyDepthOrApi(["application", "adapters", "infrastructure", "presentation"]) },
    },
    {
      name: "application-cannot-depend-on-outer",
      comment:
        "application 只能依赖 domain；不得依赖 adapters/infrastructure/presentation/api。",
      severity: "error",
      from: { path: anyDepth(["application"]) },
      to: { path: anyDepthOrApi(["adapters", "infrastructure", "presentation"]) },
    },
    {
      name: "adapters-cannot-depend-on-outer",
      comment:
        "adapters 只能依赖 application/domain；不得依赖 infrastructure/presentation/api。",
      severity: "error",
      from: { path: anyDepth(["adapters"]) },
      to: { path: anyDepthOrApi(["infrastructure", "presentation"]) },
    },
    {
      name: "infrastructure-cannot-depend-on-presentation",
      comment: "infrastructure 实现端口，不得依赖 presentation（含 src/app/）。",
      severity: "error",
      from: { path: anyDepth(["infrastructure"]) },
      to: { path: PRESENTATION },
    },
    {
      name: "presentation-cannot-depend-on-use-cases-or-repos",
      comment:
        "presentation/app 只经 adapters 触发业务，不得直接依赖 use case(application) 或 repository/gateway(infrastructure)。",
      severity: "error",
      from: { path: PRESENTATION },
      to: { path: anyDepth(["application", "infrastructure"]) },
    },
    {
      name: "no-react-in-core",
      comment:
        "domain/application/adapters/infrastructure 禁止 import react；React 只允许出现在 presentation 与 app。",
      severity: "error",
      from: { path: anyDepth(["domain", "application", "adapters", "infrastructure"]) },
      to: { path: REACT },
    },
  ],
  options: {
    // 解析 tsconfig 路径别名（@/... -> ./src/...，moduleResolution: bundler）。
    tsConfig: { fileName: "tsconfig.json" },
    // 让 `import type` 等仅类型依赖也参与门禁（strict / isolatedModules）。
    tsPreCompilationDeps: true,
    // 记录到 node_modules 的依赖边（用于 no-react 规则），但不深入遍历其内部。
    doNotFollow: { path: "node_modules" },
    enhancedResolveOptions: {
      exportsFields: ["exports"],
      conditionNames: ["import", "require", "node", "default", "types"],
      extensions: [".js", ".jsx", ".ts", ".tsx", ".d.ts"],
    },
  },
};
