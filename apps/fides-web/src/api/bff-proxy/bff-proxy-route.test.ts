import { beforeEach, describe, expect, it, vi } from "vitest";

import { proxyBffRequest } from "./bff-proxy-route";

const loggerMocks = vi.hoisted(() => ({
  logInfo: vi.fn(),
  logWarn: vi.fn(),
  logError: vi.fn(),
}));

vi.mock("@/infrastructure/bff/proxy-config", () => ({
  getBffProxyBaseUrl: vi.fn(async () => "http://fides-bff:8000/api/v1"),
}));
vi.mock("@/infrastructure/observability/server-logger", () => ({
  createRequestLogContext: vi.fn(() => ({
    trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
    span_id: "00f067aa0ba902b7",
    request_id: "req-1",
  })),
  serverLogger: {
    info: loggerMocks.logInfo,
    warn: loggerMocks.logWarn,
    error: loggerMocks.logError,
  },
}));

describe("proxyBffRequest", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    loggerMocks.logInfo.mockClear();
    loggerMocks.logWarn.mockClear();
    loggerMocks.logError.mockClear();
  });

  it("forwards method, path, query, headers and body to the internal BFF", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 201 }));
    vi.stubGlobal("fetch", fetcher);

    const response = await proxyBffRequest(
      new Request("http://fides-web.local/api/v1/loan-applications?expand=quote", {
        method: "POST",
        headers: {
          Authorization: "Bearer token",
          "Idempotency-Key": "idem-1",
          "Content-Type": "application/json",
          Host: "fides-web.local",
        },
        body: JSON.stringify({ quoteId: "quote_1" }),
      }),
      { params: Promise.resolve({ path: ["loan-applications"] }) },
    );

    expect(response.status).toBe(201);
    expect(fetcher).toHaveBeenCalledWith(
      new URL("http://fides-bff:8000/api/v1/loan-applications?expand=quote"),
      expect.objectContaining({
        method: "POST",
        body: expect.any(ReadableStream),
      }),
    );
    const init = fetcher.mock.calls[0]?.[1] as RequestInit;
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer token");
    expect(headers.get("Idempotency-Key")).toBe("idem-1");
    expect(headers.get("Host")).toBeNull();
    expect(loggerMocks.logInfo).toHaveBeenCalledWith(
      "bff_proxy.request",
      expect.objectContaining({
        route: "/api/v1/:path*",
        status: 201,
        trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
        span_id: "00f067aa0ba902b7",
        request_id: "req-1",
      }),
    );
    expect(loggerMocks.logInfo.mock.calls[0]?.[1]).not.toHaveProperty("authorization");
    expect(loggerMocks.logInfo.mock.calls[0]?.[1]).not.toHaveProperty("body");
  });

  it("logs a stable error when the BFF request fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("network failed with token=secret");
      }),
    );

    await expect(
      proxyBffRequest(
        new Request("http://fides-web.local/api/v1/loan-applications", {
          method: "GET",
          headers: {
            Authorization: "Bearer token",
            Cookie: "session=secret",
          },
        }),
        { params: Promise.resolve({ path: ["loan-applications"] }) },
      ),
    ).rejects.toThrow(/network failed/);

    expect(loggerMocks.logError).toHaveBeenCalledWith(
      "bff_proxy.error",
      expect.objectContaining({
        route: "/api/v1/:path*",
        error_code: "FIDES-DEPENDENCY-0001",
        error_type: "Error",
        trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
      }),
    );
    expect(loggerMocks.logError.mock.calls[0]?.[1]).not.toHaveProperty("authorization");
    expect(loggerMocks.logError.mock.calls[0]?.[1]).not.toHaveProperty("cookie");
  });

  it("logs downstream BFF server errors with stable error code", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("downstream unavailable", { status: 503 })));

    const response = await proxyBffRequest(
      new Request("http://fides-web.local/api/v1/loan-applications", { method: "GET" }),
      { params: Promise.resolve({ path: ["loan-applications"] }) },
    );

    expect(response.status).toBe(503);
    expect(loggerMocks.logError).toHaveBeenCalledWith(
      "bff_proxy.error",
      expect.objectContaining({
        route: "/api/v1/:path*",
        status: 503,
        error_code: "FIDES-DEPENDENCY-0001",
        error_type: "HttpStatus",
      }),
    );
  });

  it("bounds stalled BFF requests with a timeout log", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: URL, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          }),
      ),
    );

    const result = proxyBffRequest(
      new Request("http://fides-web.local/api/v1/loan-applications", { method: "GET" }),
      { params: Promise.resolve({ path: ["loan-applications"] }) },
    );
    const rejection = expect(result).rejects.toThrow(/aborted/);
    await vi.advanceTimersByTimeAsync(10_000);

    await rejection;
    expect(loggerMocks.logError).toHaveBeenCalledWith(
      "bff_proxy.error",
      expect.objectContaining({
        route: "/api/v1/:path*",
        error_code: "FIDES-DEPENDENCY-0001",
        error_type: "TimeoutError",
      }),
    );
    vi.useRealTimers();
  });
});
