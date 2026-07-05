import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    files: ["**/*.{ts,tsx}"],
    ignores: ["src/config/env.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "MemberExpression[object.object.name='process'][object.property.name='env']",
          message: "Read process.env only through src/config/env.ts.",
        },
        {
          selector: "CallExpression[callee.object.name='console']",
          message: "Use the unified fides-web server logger instead of console output.",
        },
        {
          selector: "MemberExpression[property.name='console']",
          message: "Use the unified fides-web server logger instead of console output.",
        },
        {
          selector: "Identifier[name='console']",
          message: "Use the unified fides-web server logger instead of console output.",
        },
      ],
    },
  },
  {
    files: ["src/infrastructure/observability/server-logger.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "MemberExpression[object.object.name='process'][object.property.name='env']",
          message: "Read process.env only through src/config/env.ts.",
        },
      ],
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
