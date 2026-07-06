import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clientEnvSchema,
  getFidesEnv,
  getSmokeEnv,
  validateNoLegacyPublicEnv,
} from "./env";

describe("env config", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("keeps the client env schema empty", () => {
    expect(clientEnvSchema.safeParse({}).success).toBe(true);
    expect(clientEnvSchema.safeParse({ NEXT_PUBLIC_FIDES_BFF_BASE_URL: "/api/v1" }).success).toBe(false);
  });

  it("fails fast when FIDES_BFF_BASE_URL is missing", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "");

    expect(() => getFidesEnv()).toThrow(/FIDES_BFF_BASE_URL/);
  });

  it("rejects malformed browser tracing headers", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("FIDES_BROWSER_TRACING_HEADERS", "x-sentry-auth=");

    expect(() => getFidesEnv()).toThrow(/FIDES_BROWSER_TRACING_HEADERS/);
  });

  it("loads server-side OTEL logs exporter configuration", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("OTEL_LOGS_EXPORTER", "otlp");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "https://otel.example/v1/logs");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_HEADERS", "x-sentry-auth=token");

    expect(getFidesEnv()).toMatchObject({
      OTEL_LOGS_EXPORTER: "otlp",
      OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: "https://otel.example/v1/logs",
      OTEL_EXPORTER_OTLP_LOGS_HEADERS: "x-sentry-auth=token",
      OTEL_SERVICE_NAME: "fides-web",
    });
  });

  it("rejects malformed server OTEL logs headers", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_HEADERS", "x-sentry-auth=");

    expect(() => getFidesEnv()).toThrow(/OTEL_EXPORTER_OTLP_LOGS_HEADERS/);
  });

  it("requires server OTEL logs endpoint when OTLP export is enabled", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("OTEL_LOGS_EXPORTER", "otlp");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "");

    expect(() => getFidesEnv()).toThrow(/OTEL_EXPORTER_OTLP_LOGS_ENDPOINT/);
  });

  it("rejects legacy NEXT_PUBLIC runtime variables", () => {
    vi.stubEnv("NEXT_PUBLIC_FIDES_OTP_ADAPTER", "real");

    expect(() => validateNoLegacyPublicEnv()).toThrow(/NEXT_PUBLIC_FIDES_OTP_ADAPTER/);
  });

  it("requires the smoke BFF URL only when real BFF smoke is enabled", () => {
    expect(getSmokeEnv()).toMatchObject({ realBffSmoke: false, smokePhone: "91989999" });

    vi.stubEnv("LEN43_REAL_BFF_SMOKE", "1");
    vi.stubEnv("LEN43_FIDES_BFF_BASE_URL", "");

    expect(() => getSmokeEnv()).toThrow(/LEN43_FIDES_BFF_BASE_URL/);
  });
});
