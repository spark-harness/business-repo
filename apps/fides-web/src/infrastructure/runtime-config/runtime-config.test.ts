import { afterEach, describe, expect, it, vi } from "vitest";

import {
  buildPublicRuntimeConfig,
  loadRuntimeConfig,
  validateNoLegacyPublicEnv,
} from "./runtime-config";

const ORIGINAL_ENV = { ...process.env };

describe("runtime config", () => {
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it("loads defaults, Consul JSON, and runtime env overrides in order", async () => {
    process.env.FIDES_RUNTIME_ENV = "sta";
    process.env.FIDES_RUNTIME_CONFIG_CONSUL_URL = "http://consul:8500";
    process.env.FIDES_OTP_ADAPTER = "disabled";
    process.env.FIDES_BROWSER_TRACING_HEADERS = "x-lendora-environment=sta";
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([
          {
            Value: Buffer.from(
              JSON.stringify({
                otpAdapter: "real",
                bffBaseUrl: "https://consul-bff.example/api/v1",
                browserTracing: {
                  endpoint: "https://otel.example/v1/traces",
                  headers: { "x-from-consul": "true" },
                },
              }),
            ).toString("base64"),
          },
        ]),
        { status: 200 },
      ),
    );

    await expect(loadRuntimeConfig({ fetcher })).resolves.toMatchObject({
      environment: "sta",
      otpAdapter: "disabled",
      bffBaseUrl: "https://consul-bff.example/api/v1",
      browserTracing: {
        endpoint: "https://otel.example/v1/traces",
        headers: { "x-lendora-environment": "sta" },
      },
    });
    expect(fetcher).toHaveBeenCalledWith(
      "http://consul:8500/v1/kv/spark/lendora/sta/fides-web/runtime-config",
      { cache: "no-store" },
    );
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
      internal: {
        consulUrl: "http://consul:8500",
        consulKey: "spark/lendora/prod/fides-web/runtime-config",
      },
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
    process.env.NEXT_PUBLIC_FIDES_BFF_BASE_URL = "/api/v1";

    expect(() => validateNoLegacyPublicEnv()).toThrow(/NEXT_PUBLIC_FIDES_BFF_BASE_URL/);
  });

  it("fails production real OTP config without an explicit BFF base URL", async () => {
    process.env.FIDES_RUNTIME_ENV = "prod";
    process.env.FIDES_OTP_ADAPTER = "real";

    await expect(loadRuntimeConfig()).rejects.toThrow(/explicit BFF base URL/);
  });
});
