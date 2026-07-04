import { afterEach, describe, expect, it, vi } from "vitest";

import {
  buildPublicRuntimeConfig,
  loadRuntimeConfig,
  validateNoLegacyPublicEnv,
} from "./runtime-config";

describe("runtime config", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("loads defaults and runtime env overrides", async () => {
    vi.stubEnv("FIDES_RUNTIME_ENV", "sta");
    vi.stubEnv("FIDES_OTP_ADAPTER", "disabled");
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://fides-bff:8000/api/v1");
    vi.stubEnv("FIDES_BROWSER_TRACING_ENDPOINT", "https://otel.example/v1/traces");
    vi.stubEnv("FIDES_BROWSER_TRACING_HEADERS", "x-lendora-environment=sta");

    await expect(loadRuntimeConfig()).resolves.toMatchObject({
      environment: "sta",
      otpAdapter: "disabled",
      bffBaseUrl: "/api/v1",
      internal: {
        bffBaseUrl: "http://fides-bff:8000/api/v1",
      },
      browserTracing: {
        endpoint: "https://otel.example/v1/traces",
        headers: { "x-lendora-environment": "sta" },
      },
    });
  });

  it("exposes only whitelisted public runtime config fields", () => {
    const publicConfig = buildPublicRuntimeConfig({
      environment: "prod",
      otpAdapter: "real",
      bffBaseUrl: "/api/v1",
      browserTracing: {
        endpoint: "https://otel.example/v1/traces",
        headers: { "x-public": "1" },
      },
      internal: { bffBaseUrl: "http://fides-bff:8000/api/v1" },
    });

    expect(publicConfig).toEqual({
      otpAdapter: "real",
      bffBaseUrl: "/api/v1",
      browserTracing: {
        endpoint: "https://otel.example/v1/traces",
        environment: "prod",
        headers: { "x-public": "1" },
      },
    });
    expect(publicConfig).not.toHaveProperty("environment");
    expect(publicConfig).not.toHaveProperty("internal");
  });

  it("rejects legacy NEXT_PUBLIC runtime variables", () => {
    vi.stubEnv("NEXT_PUBLIC_FIDES_BFF_BASE_URL", "/api/v1");

    expect(() => validateNoLegacyPublicEnv()).toThrow(/NEXT_PUBLIC_FIDES_BFF_BASE_URL/);
  });

  it("fails fast when required runtime env is missing", async () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "");

    await expect(loadRuntimeConfig()).rejects.toThrow(/FIDES_BFF_BASE_URL/);
  });

  it("fails fast when browser tracing headers are malformed", async () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://fides-bff:8000/api/v1");
    vi.stubEnv("FIDES_BROWSER_TRACING_HEADERS", "x-sentry-auth=");

    await expect(loadRuntimeConfig()).rejects.toThrow(/FIDES_BROWSER_TRACING_HEADERS/);
  });

  it("fails production real OTP config without an explicit BFF base URL", async () => {
    vi.stubEnv("FIDES_RUNTIME_ENV", "prod");
    vi.stubEnv("FIDES_OTP_ADAPTER", "real");
    vi.stubEnv("FIDES_BFF_BASE_URL", "");

    await expect(loadRuntimeConfig()).rejects.toThrow(/FIDES_BFF_BASE_URL/);
  });
});
