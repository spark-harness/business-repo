import type { OtpAdapterMode, PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";
import { getFidesEnv, type FidesEnv } from "@/config/env";

export { validateNoLegacyPublicEnv } from "@/config/env";

export type RuntimeConfig = PublicRuntimeConfig & {
  environment: string;
  internal: {
    bffBaseUrl?: string;
  };
};

type RuntimeConfigInput = Partial<{
  otpAdapter: unknown;
  bffBaseUrl: unknown;
  browserTracing: Partial<{
    endpoint: unknown;
    headers: unknown;
  }>;
  internal: Partial<{
    bffBaseUrl: unknown;
  }>;
}>;

const DEFAULT_ENVIRONMENT = "local";
const DEFAULT_CONFIG: RuntimeConfig = {
  environment: DEFAULT_ENVIRONMENT,
  otpAdapter: "mock",
  bffBaseUrl: "/api/v1",
  browserTracing: { headers: {} },
  internal: {},
};

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  const env = getFidesEnv();

  const environment = env.FIDES_RUNTIME_ENV;

  const baseConfig: RuntimeConfig = {
    ...DEFAULT_CONFIG,
    environment,
    internal: {},
  };
  const envConfig = loadEnvOverrides(env);
  const config = mergeRuntimeConfig(baseConfig, envConfig);

  validateRuntimeConfig(config);
  return config;
}

export function buildPublicRuntimeConfig(config: RuntimeConfig): PublicRuntimeConfig {
  return {
    otpAdapter: config.otpAdapter,
    bffBaseUrl: "/api/v1",
    browserTracing: {
      endpoint: config.browserTracing.endpoint,
      environment: config.environment,
      headers: { ...config.browserTracing.headers },
    },
  };
}

function loadEnvOverrides(env: FidesEnv): RuntimeConfigInput {
  return {
    otpAdapter: env.FIDES_OTP_ADAPTER,
    internal: {
      bffBaseUrl: env.FIDES_BFF_BASE_URL,
    },
    browserTracing: {
      endpoint: env.FIDES_BROWSER_TRACING_ENDPOINT,
      headers: parseHeaders(env.FIDES_BROWSER_TRACING_HEADERS),
    },
  };
}

function mergeRuntimeConfig(config: RuntimeConfig, input: RuntimeConfigInput): RuntimeConfig {
  return {
    ...config,
    otpAdapter: parseOtpAdapter(input.otpAdapter) ?? config.otpAdapter,
    bffBaseUrl: readString(input.bffBaseUrl) ?? config.bffBaseUrl,
    browserTracing: {
      endpoint: readString(input.browserTracing?.endpoint) ?? config.browserTracing.endpoint,
      headers: isRecord(input.browserTracing?.headers)
        ? normalizeHeaders(input.browserTracing.headers)
        : config.browserTracing.headers,
    },
    internal: {
      ...config.internal,
      bffBaseUrl: readString(input.internal?.bffBaseUrl) ?? config.internal.bffBaseUrl,
    },
  };
}

function validateRuntimeConfig(config: RuntimeConfig) {
  if (!config.internal.bffBaseUrl) {
    throw new Error("FIDES_BFF_BASE_URL is required");
  }
  if (config.environment === "prod" && config.otpAdapter === "real" && !config.internal.bffBaseUrl) {
    throw new Error("Production fides runtime config must provide an explicit BFF base URL");
  }
}

function parseOtpAdapter(value: unknown): OtpAdapterMode | undefined {
  if (value === "real" || value === "mock" || value === "disabled") {
    return value;
  }
  if (value === undefined || value === null || value === "") {
    return undefined;
  }
  throw new Error(`Unsupported FIDES_OTP_ADAPTER: ${String(value)}`);
}

function parseHeaders(raw: string | undefined): Record<string, string> | undefined {
  if (!raw) {
    return undefined;
  }
  return raw.split(",").reduce<Record<string, string>>((headers, item) => {
    const index = item.indexOf("=");
    const key = index >= 0 ? item.slice(0, index).trim() : "";
    const value = index >= 0 ? item.slice(index + 1).trim() : "";
    if (!key || !value) {
      throw new Error("FIDES_BROWSER_TRACING_HEADERS must use comma-separated key=value pairs");
    }
    headers[key] = value;
    return headers;
  }, {});
}

function normalizeHeaders(headers: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(headers)
      .map(([key, value]) => [key.trim(), readString(value)] as const)
      .filter((entry): entry is [string, string] => Boolean(entry[0] && entry[1])),
  );
}

function readString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
