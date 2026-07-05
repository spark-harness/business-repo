import { beforeEach, describe, expect, it, vi } from "vitest";

import { getRuntimeConfigResponse } from "./runtime-config-route";

const loggerMocks = vi.hoisted(() => ({
  logInfo: vi.fn(),
  logError: vi.fn(),
}));
const runtimeConfigMocks = vi.hoisted(() => ({
  getPublicRuntimeConfig: vi.fn(),
}));

vi.mock("@/infrastructure/observability/server-logger", () => ({
  createRequestLogContext: vi.fn(() => ({
    trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
    span_id: "00f067aa0ba902b7",
    request_id: "req-1",
  })),
  serverLogger: {
    info: loggerMocks.logInfo,
    error: loggerMocks.logError,
  },
}));
vi.mock("./get-public-runtime-config", () => ({
  getPublicRuntimeConfig: runtimeConfigMocks.getPublicRuntimeConfig,
}));

describe("getRuntimeConfigResponse", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    loggerMocks.logInfo.mockClear();
    loggerMocks.logError.mockClear();
    runtimeConfigMocks.getPublicRuntimeConfig.mockReset();
  });

  it("logs runtime config requests with trace context and stable fields", async () => {
    runtimeConfigMocks.getPublicRuntimeConfig.mockResolvedValue({
      otpAdapter: "real",
      bffBaseUrl: "/api/v1",
      browserTracing: { headers: {} },
    });

    const response = await getRuntimeConfigResponse(
      new Request("http://fides-web.local/api/runtime-config"),
    );

    expect(response.status).toBe(200);
    expect(loggerMocks.logInfo).toHaveBeenCalledWith(
      "runtime_config.request",
      expect.objectContaining({
        route: "/api/runtime-config",
        status: 200,
        config_source: "env",
        trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
        span_id: "00f067aa0ba902b7",
        request_id: "req-1",
      }),
    );
    expect(loggerMocks.logInfo.mock.calls[0]?.[1]).not.toHaveProperty("headers");
    expect(loggerMocks.logInfo.mock.calls[0]?.[1]).not.toHaveProperty("internal");
  });

  it("logs runtime config failures without env values or secrets", async () => {
    runtimeConfigMocks.getPublicRuntimeConfig.mockRejectedValue(new Error("missing secret value"));

    await expect(
      getRuntimeConfigResponse(new Request("http://fides-web.local/api/runtime-config")),
    ).rejects.toThrow(/missing secret/);

    expect(loggerMocks.logError).toHaveBeenCalledWith(
      "runtime_config.error",
      expect.objectContaining({
        route: "/api/runtime-config",
        error_code: "FIDES-SYSTEM-0001",
        error_type: "Error",
        trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
      }),
    );
    expect(loggerMocks.logError.mock.calls[0]?.[1]).not.toHaveProperty("secret");
    expect(loggerMocks.logError.mock.calls[0]?.[1]).not.toHaveProperty("body");
  });
});
