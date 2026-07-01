import type { OtpAdapterMode, PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

export type RuntimeConfig = PublicRuntimeConfig & {
  environment: string;
  internal: {
    consulUrl?: string;
    consulKey: string;
  };
};

type RuntimeConfigInput = Partial<{
  otpAdapter: unknown;
  bffBaseUrl: unknown;
  browserTracing: Partial<{
    endpoint: unknown;
    headers: unknown;
  }>;
}>;

type LoadRuntimeConfigOptions = {
  fetcher?: typeof fetch;
};

const DEFAULT_ENVIRONMENT = "local";
const DEFAULT_CONFIG: RuntimeConfig = {
  environment: DEFAULT_ENVIRONMENT,
  otpAdapter: "mock",
  bffBaseUrl: "/api/v1",
  browserTracing: { headers: {} },
  internal: {
    consulKey: "spark/lendora/local/fides-web/runtime-config",
  },
};

const LEGACY_PUBLIC_ENV = [
  "NEXT_PUBLIC_FIDES_OTP_ADAPTER",
  "NEXT_PUBLIC_FIDES_BFF_BASE_URL",
  "NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
  "NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_HEADERS",
] as const;

export async function loadRuntimeConfig(options: LoadRuntimeConfigOptions = {}): Promise<RuntimeConfig> {
  validateNoLegacyPublicEnv();

  const environment = readString(process.env.FIDES_RUNTIME_ENV) ?? DEFAULT_ENVIRONMENT;
  const consulUrl = readString(process.env.FIDES_RUNTIME_CONFIG_CONSUL_URL);
  const consulKey =
    readString(process.env.FIDES_RUNTIME_CONFIG_CONSUL_KEY) ??
    `spark/lendora/${environment}/fides-web/runtime-config`;

  const baseConfig: RuntimeConfig = {
    ...DEFAULT_CONFIG,
    environment,
    internal: { consulUrl, consulKey },
  };
  const consulConfig = await loadConsulConfig(consulUrl, consulKey, options.fetcher);
  const envConfig = loadEnvOverrides();
  const config = mergeRuntimeConfig(mergeRuntimeConfig(baseConfig, consulConfig), envConfig);

  validateRuntimeConfig(config);
  return config;
}

export function buildPublicRuntimeConfig(config: RuntimeConfig): PublicRuntimeConfig {
  return {
    otpAdapter: config.otpAdapter,
    bffBaseUrl: config.bffBaseUrl,
    browserTracing: {
      endpoint: config.browserTracing.endpoint,
      environment: config.environment,
      headers: { ...config.browserTracing.headers },
    },
  };
}

export function validateNoLegacyPublicEnv() {
  const present = LEGACY_PUBLIC_ENV.filter((name) => process.env[name]);
  if (present.length > 0) {
    throw new Error(`Legacy public runtime variables are not supported: ${present.join(", ")}`);
  }
}

async function loadConsulConfig(
  consulUrl: string | undefined,
  consulKey: string,
  fetcher: typeof fetch = fetch,
): Promise<RuntimeConfigInput> {
  if (!consulUrl) {
    return {};
  }

  const response = await fetcher(`${trimTrailingSlash(consulUrl)}/v1/kv/${encodeConsulKey(consulKey)}`, {
    cache: "no-store",
  });
  if (response.status === 404) {
    return {};
  }
  if (!response.ok) {
    throw new Error(`Failed to load fides runtime config from Consul: ${response.status}`);
  }

  const entries = (await response.json()) as Array<{ Value?: string }> | null;
  const value = entries?.[0]?.Value;
  if (!value) {
    return {};
  }
  return JSON.parse(Buffer.from(value, "base64").toString("utf8")) as RuntimeConfigInput;
}

function loadEnvOverrides(): RuntimeConfigInput {
  return {
    otpAdapter: process.env.FIDES_OTP_ADAPTER,
    bffBaseUrl: process.env.FIDES_BFF_BASE_URL,
    browserTracing: {
      endpoint: process.env.FIDES_BROWSER_TRACING_ENDPOINT,
      headers: parseHeaders(process.env.FIDES_BROWSER_TRACING_HEADERS),
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
  };
}

function validateRuntimeConfig(config: RuntimeConfig) {
  if (!config.bffBaseUrl) {
    throw new Error("FIDES_BFF_BASE_URL is required");
  }
  if (config.environment === "prod" && config.otpAdapter === "real" && config.bffBaseUrl === "/api/v1") {
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
    if (index <= 0) {
      return headers;
    }
    headers[item.slice(0, index).trim()] = item.slice(index + 1).trim();
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

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

function encodeConsulKey(key: string): string {
  return key
    .split("/")
    .map((part) => encodeURIComponent(part))
    .join("/");
}
